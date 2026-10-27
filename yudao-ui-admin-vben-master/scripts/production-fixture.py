#!/usr/bin/env python3
"""Explicit, loopback-only CI peers; never Zijinqiao/site emulators.

Each source is a separate process/listener with independent token, failure state
and request audit. Points deliberately collide, while values differ by 1000.
Only source-a also provides the declared system RPC fixture. No production build
imports this file. All identities and tokens are disposable TEST FIXTURE data.
"""
import argparse
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
SOURCES = {
    'source-a': {'port': 48084, 'token': 'ci-disposable-realdb-source-a-token', 'offset': 0},
    'source-b': {'port': 48085, 'token': 'ci-disposable-realdb-source-b-token', 'offset': 1000},
}


class FixtureServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, source):
        assert source in SOURCES
        self.source = source
        self.spec = SOURCES[source]
        self.lock = threading.Lock()
        self.state = {'sourceId': source, 'mode': 'normal', 'delayMs': 0,
                      'historyDelayMs': 0, 'requests': []}
        super().__init__(address, Handler)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Full URLs may contain disposable tokens; never log them.

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
        self.respond({'code': code, 'data': data, 'msg': 'TEST FIXTURE contract peer'})

    def do_GET(self):
        parsed = urlparse(self.path)
        path, query = parsed.path, parse_qs(parsed.query)
        value = lambda key: query.get(key, [''])[0]
        with self.server.lock:
            if path == '/__fixture/state':
                self.respond(dict(self.server.state))
                return
            event = {'sourceId': self.server.source, 'method': 'GET', 'path': path}
            self.server.state['requests'].append(event)
            mode = self.server.state['mode']
            delay = (self.server.state['historyDelayMs'] if path == '/api/hisdata'
                     and self.server.state['historyDelayMs'] else self.server.state['delayMs'])
        if path.startswith('/rpc-api/') and self.server.source != 'source-a':
            self.respond({'code': 404, 'msg': 'System RPC exists only on source-a test peer'}, 404)
        elif path == '/rpc-api/system/oauth2/token/check':
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
            authenticated = value('token') == self.server.spec['token']
            with self.server.lock:
                event.update({'authenticated': authenticated})
            if not authenticated or mode == 'unauthorized':
                self.respond({'code': 403, 'msg': 'TEST FIXTURE rejected vendor authentication'}, 403)
                return
            names = value('names').split(',')
            if not names or any(not re.fullmatch(r'fixture\.device\.[1-4]\.(pressure|temperature)', n) for n in names):
                self.respond({'code': 400, 'msg': 'Only explicit fixture points exist'}, 400)
                return
            with self.server.lock:
                event.update({'names': names, 'mode': mode})
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
                base = self.server.spec['offset'] + number * 10 + (0.25 if name.endswith('pressure') else 0.75)
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
                assert isinstance(data, dict) and set(data) <= {'mode', 'delayMs', 'historyDelayMs'}
                assert data.get('mode', 'normal') in {'normal', 'failure', 'unauthorized', 'missing', 'stale', 'malformed', 'received'}
                assert all(isinstance(data.get(k, 0), int) and 0 <= data.get(k, 0) <= 5000
                           for k in ('delayMs', 'historyDelayMs'))
            except (ValueError, TypeError, AssertionError):
                self.respond({'code': 400}, 400)
                return
            with self.server.lock:
                self.server.state.update(data)
            self.respond({'ok': True, 'sourceId': self.server.source})
        elif (self.server.source == 'source-a'
              and path in ('/rpc-api/infra/api-access-log/create', '/rpc-api/infra/api-error-log/create')):
            self.common(True)  # Disposable log sink; bodies are not retained.
        else:
            with self.server.lock:
                self.server.state['requests'].append({'sourceId': self.server.source, 'method': 'POST', 'path': path, 'rejected': True})
            self.respond({'code': 405, 'msg': 'Vendor mutations are forbidden'}, 405)


if __name__ == '__main__':
    if os.environ.get('CI_CONTRACT_FIXTURE') != 'explicit-test-only':
        raise SystemExit('Requires CI_CONTRACT_FIXTURE=explicit-test-only; not a production service')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', choices=SOURCES, default='source-a')
    args = parser.parse_args()
    server = FixtureServer(('127.0.0.1', SOURCES[args.source]['port']), args.source)
    print(f'TEST FIXTURE ONLY: independent {args.source} contract peer on loopback:{server.server_port}', flush=True)
    server.serve_forever()
