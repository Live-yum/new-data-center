#!/usr/bin/env python3
"""Explicit, loopback-only CI contract fixture; never a Zijinqiao/site emulator.

The real packaged IoT service retains its token/tenant/permission filters and real
RealDbClient. Only its external system RPC and vendor HTTP peers are fixtures.
No production build imports this file. All identities/tokens are disposable.
"""
import json
import os
import re
import threading
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

TOKENS = {
    'ci-admin-tenant-7001': (700101, 7001),
    'ci-denied-tenant-7001': (700102, 7001),
    'ci-admin-tenant-7002': (700201, 7002),
}
PERMISSIONS = {
    'iot:device:query', 'iot:device:configure', 'iot:dashboard:query',
    'iot:dashboard:configure', 'iot:data:query', 'iot:data:export',
}
VENDOR_TOKEN = 'ci-disposable-realdb-contract-token'
LOCK = threading.Lock()
STATE = {'mode': 'normal', 'delayMs': 0, 'requests': []}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Never log full URLs: vendor and token-check URLs contain tokens.

    def respond(self, value, status=200):
        body = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def common(self, data, code=0):
        self.respond({'code': code, 'data': data, 'msg': 'CI contract fixture'})

    def do_GET(self):
        parsed = urlparse(self.path)
        path, query = parsed.path, parse_qs(parsed.query)
        value = lambda key: query.get(key, [''])[0]
        with LOCK:
            if path == '/__fixture/state':
                self.respond(dict(STATE))
                return
            event = {'method': 'GET', 'path': path}
            STATE['requests'].append(event)
            mode, delay = STATE['mode'], STATE['delayMs']
        if path == '/rpc-api/system/oauth2/token/check':
            identity = TOKENS.get(value('accessToken'))
            if not identity:
                self.common(None, 401)
            else:
                self.common({'userId': identity[0], 'userType': 2,
                             'tenantId': identity[1], 'scopes': []})
        elif path == '/rpc-api/system/permission/has-any-permissions':
            permissions = [p for item in query.get('permissions', []) for p in item.split(',')]
            self.common(value('userId') in ('700101', '700201')
                        and bool(set(permissions) & PERMISSIONS))
        elif path == '/rpc-api/system/tenant/valid':
            self.common(value('id') in ('7001', '7002'),
                        0 if value('id') in ('7001', '7002') else 403)
        elif path == '/rpc-api/system/tenant/id-list':
            self.common([7001, 7002])
        elif path in ('/api/realdata', '/api/hisdata'):
            if value('token') != VENDOR_TOKEN:
                self.respond({'code': 403}, 403)
                return
            names = value('names').split(',')
            if not names or any(not re.fullmatch(r'fixture\.device\.[1-4]\.(pressure|temperature)', n) for n in names):
                self.respond({'code': 400, 'msg': 'Only explicit fixture points exist'}, 400)
                return
            with LOCK:
                event.update({'names': names, 'authenticated': True})
            time.sleep(delay / 1000)
            if mode == 'failure':
                self.respond({'code': 503, 'msg': 'Explicit contract-test outage'}, 503)
                return
            if mode == 'malformed':
                self.respond({'unrecognized_shape': []})
                return
            now = datetime.now(timezone.utc).replace(tzinfo=None)
            items = []
            for name in names:
                number = int(name.split('.')[2])
                base = number * 10 + (0.25 if name.endswith('pressure') else 0.75)
                quality = ('GOOD', 'BAD', 'UNKNOWN', 'UNCERTAIN')[number - 1]
                if path == '/api/realdata':
                    timestamp = now - timedelta(minutes=10) if mode == 'stale' else now
                    item = {'name': name, 'val': None if mode == 'missing' else base,
                            'quality': quality, 'time': timestamp.isoformat(timespec='milliseconds')}
                    if mode == 'received':
                        del item['time']
                else:
                    start = datetime.fromisoformat(value('startTime'))
                    end = datetime.fromisoformat(value('endTime'))
                    interval = int(value('interval'))
                    if interval < 1000 or end <= start or (end-start).total_seconds() / (interval/1000) > 10000:
                        self.respond({'code': 400}, 400)
                        return
                    points, at = [], start
                    while at <= end:
                        points.append({'time': at.isoformat(timespec='milliseconds'),
                                       'val': None if mode == 'missing' or len(points) == 2 else base + len(points) / 100,
                                       'quality': quality})
                        at += timedelta(milliseconds=interval)
                    item = {'name': name, 'vals': points}
                items.append(item)
            self.respond({'items': items})
        else:
            self.respond({'code': 404, 'msg': 'Not a declared fixture endpoint'}, 404)

    def do_POST(self):
        path = urlparse(self.path).path
        length = int(self.headers.get('Content-Length', '0'))
        if length > 65536:
            self.respond({'code': 413}, 413)
            return
        body = self.rfile.read(length)
        if path == '/__fixture/control':
            try:
                data = json.loads(body)
                assert set(data) <= {'mode', 'delayMs'}
                assert data.get('mode', 'normal') in {'normal', 'failure', 'missing', 'stale', 'malformed', 'received'}
                assert 0 <= data.get('delayMs', 0) <= 5000
            except (ValueError, TypeError, AssertionError):
                self.respond({'code': 400}, 400)
                return
            with LOCK:
                STATE.update(data)
            self.respond({'ok': True})
        elif path in ('/rpc-api/infra/api-access-log/create', '/rpc-api/infra/api-error-log/create'):
            self.common(True)  # Test-only disposable log sink; bodies are not retained.
        else:
            with LOCK:
                STATE['requests'].append({'method': 'POST', 'path': path, 'rejected': True})
            self.respond({'code': 405, 'msg': 'Vendor mutations are forbidden'}, 405)


if __name__ == '__main__':
    if os.environ.get('CI_CONTRACT_FIXTURE') != 'explicit-test-only':
        raise SystemExit('Requires CI_CONTRACT_FIXTURE=explicit-test-only; not a production service')
    server = ThreadingHTTPServer(('127.0.0.1', 48084), Handler)
    print('TEST FIXTURE ONLY: external RPC + vendor contracts on loopback:48084', flush=True)
    server.serve_forever()
