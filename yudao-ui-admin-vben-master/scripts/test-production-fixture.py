#!/usr/bin/env python3
"""Unit checks of the explicitly labelled fixture, not production API evidence."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import unittest
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

PATH = Path(__file__).with_name('production-fixture.py')
SPEC = importlib.util.spec_from_file_location('fixture', PATH)
FIXTURE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FIXTURE)


class FixtureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = FIXTURE.FixtureServer(('127.0.0.1', 0), 'source-a')
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.other = FIXTURE.FixtureServer(('127.0.0.1', 0), 'source-b')
        cls.other_thread = threading.Thread(target=cls.other.serve_forever, daemon=True)
        cls.other_thread.start()
        cls.base = f'http://127.0.0.1:{cls.server.server_port}'

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()
        cls.other.shutdown()
        cls.other.server_close()
        cls.other_thread.join()

    def setUp(self):
        for server in (self.server, self.other):
            with server.lock:
                server.active_token = server.spec['token']
                server.session_number = 0
                server.state.update({'mode': 'normal', 'delayMs': 0, 'historyDelayMs': 0, 'requests': []})

    def call(self, path, method='GET', data=None, other=False):
        base = f'http://127.0.0.1:{self.other.server_port}' if other else self.base
        request = Request(base+path, method=method,
                          data=None if data is None else json.dumps(data).encode())
        try:
            response = urlopen(request, timeout=5)
        except HTTPError as error:
            response = error
        with response:
            return response.status, json.loads(response.read())

    def test_requires_explicit_acknowledgement(self):
        env = {k: v for k, v in os.environ.items() if k != 'CI_CONTRACT_FIXTURE'}
        result = subprocess.run([sys.executable, str(PATH)], capture_output=True, env=env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b'not a production service', result.stderr)

    def test_auth_and_permission_fixture_is_not_allow_all(self):
        prefix = '/rpc-api/system/'
        self.assertEqual(self.call(prefix+'oauth2/token/check?accessToken=invalid')[1]['code'], 401)
        token = self.call(prefix+'oauth2/token/check?accessToken=ci-admin-tenant-7001')[1]
        self.assertEqual(token['data']['tenantId'], 7001)
        self.assertEqual(token['data']['userId'], 700101)
        for user, permission, allowed in [('700101', 'iot:device:query', True), ('700102', 'iot:device:query', False), ('700101', 'system:manage', False)]:
            response = self.call(prefix+f'permission/has-any-permissions?userId={user}&permissions={permission}')
            self.assertIs(response[1]['data'], allowed)
        self.assertEqual(self.call(prefix+'tenant/valid?id=9999')[1]['code'], 403)

    def test_vendor_requires_auth_known_points_and_readonly_contract(self):
        path = '/api/realdata?names=fixture.device.1.pressure'
        self.assertEqual(self.call(path)[0], 403)
        path += '&token='+FIXTURE.SOURCES['source-a']['token']
        point = self.call(path)[1]['items'][0]
        self.assertEqual(point['val'], 10.25)
        self.assertEqual(point['quality'], 'GOOD')
        self.assertEqual(self.call('/api/realdata?names=arbitrary&token='+FIXTURE.SOURCES['source-a']['token'])[0], 400)
        self.assertEqual(self.call('/api/realdata', 'POST', {'items': []})[0], 405)

    def test_fixture_control_is_bounded(self):
        self.assertEqual(self.call('/__fixture/control', 'POST', {'delayMs': 99999})[0], 400)
        self.assertEqual(self.call('/__fixture/control', 'POST', {'mode': 'missing'})[0], 200)
        value = self.call('/api/realdata?names=fixture.device.1.pressure&token='+FIXTURE.SOURCES['source-a']['token'])[1]
        self.assertIsNone(value['items'][0]['val'])
        self.call('/__fixture/control', 'POST', {'mode': 'normal'})

    def test_independent_sources_collide_on_names_not_values_or_credentials(self):
        path = '/api/realdata?names=fixture.device.1.pressure&token='
        a = FIXTURE.SOURCES['source-a']['token']
        b = FIXTURE.SOURCES['source-b']['token']
        self.assertEqual(self.call(path+a, other=True)[0], 403)
        self.assertEqual(self.call(path+b)[0], 403)
        self.assertEqual(self.call(path+a)[1]['items'][0]['val'], 10.25)
        self.assertEqual(self.call(path+b, other=True)[1]['items'][0]['val'], 1010.25)
        self.assertEqual(self.call('/rpc-api/system/tenant/id-list', other=True)[0], 404)
        self.assertEqual(self.call('/__fixture/state', other=True)[1]['sourceId'], 'source-b')

    def test_source_failure_and_auth_control_are_independent_and_redacted(self):
        path = '/api/realdata?names=fixture.device.1.pressure&token='
        for mode in ('failure', 'unauthorized'):
            self.call('/__fixture/control', 'POST', {'mode': mode}, other=True)
            self.assertNotEqual(self.call(path+FIXTURE.SOURCES['source-b']['token'], other=True)[0], 200)
            self.assertEqual(self.call(path+FIXTURE.SOURCES['source-a']['token'])[1]['items'][0]['val'], 10.25)
        self.call('/__fixture/control', 'POST', {'mode': 'normal'}, other=True)
        for other in (False, True):
            audit = json.dumps(self.call('/__fixture/state', other=other)[1])
            for spec in FIXTURE.SOURCES.values():
                self.assertNotIn(spec['token'], audit)

    def test_source_login_uses_own_credentials_and_rotates_rejected_sessions(self):
        a, b = FIXTURE.SOURCES['source-a'], FIXTURE.SOURCES['source-b']
        login = lambda spec: '/api/userlogin?' + urlencode({'user': spec['username'], 'pass': spec['password']})
        point = '/api/realdata?names=fixture.device.1.pressure&token='
        self.assertEqual(self.call(login(a), other=True)[0], 403)
        self.assertEqual(self.call(login(b))[0], 403)
        self.call('/__fixture/control', 'POST', {'mode': 'unauthorized'}, other=True)
        self.assertEqual(self.call(point+b['token'], other=True)[0], 403)
        self.assertEqual(self.call(login(b), other=True)[0], 403)
        self.call('/__fixture/control', 'POST', {'mode': 'normal'}, other=True)
        # Clearing the fixture failure does not resurrect a rejected credential.
        self.assertEqual(self.call(point+b['token'], other=True)[0], 403)
        status, response = self.call(login(b), other=True)
        self.assertEqual(status, 200)
        session = response['token']
        self.assertNotEqual(session, b['token'])
        self.assertEqual(self.call(point+session, other=True)[1]['items'][0]['val'], 1010.25)
        self.assertEqual(self.call(point+session)[0], 403)
        self.assertEqual(self.call(point+a['token'])[1]['items'][0]['val'], 10.25)
        for other in (False, True):
            audit = json.dumps(self.call('/__fixture/state', other=other)[1])
            for secret in (a['username'], a['password'], a['token'], b['username'], b['password'], b['token'], session):
                self.assertNotIn(secret, audit)

    def test_history_delay_and_source_controls_are_bounded(self):
        self.assertEqual(self.call('/__fixture/control', 'POST', {'historyDelayMs': 5001})[0], 400)
        self.assertEqual(self.call('/__fixture/control', 'POST', {'historyDelayMs': -1})[0], 400)
        self.assertEqual(self.call('/__fixture/control', 'POST', {'sourceId': 'source-b'})[0], 400)

    def test_documented_millisecond_history_shape_and_missing_sample(self):
        query = urlencode({'names': 'fixture.device.1.pressure', 'token': FIXTURE.SOURCES['source-a']['token'],
                           'startTime': '2026-10-27T03:17:17.401', 'endTime': '2026-10-27T03:22:17.401',
                           'interval': 60000})
        status, response = self.call('/api/hisdata?'+query)
        self.assertEqual(status, 200)
        points = response['items'][0]['vals']
        self.assertEqual(len(points), 6)
        self.assertEqual(points[0]['time'], '2026-10-27T03:17:17.401')
        self.assertEqual(points[-1]['time'], '2026-10-27T03:22:17.401')
        self.assertIsNone(points[2]['val'])


if __name__ == '__main__':
    unittest.main()
