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
from urllib.request import Request, urlopen

PATH = Path(__file__).with_name('production-fixture.py')
SPEC = importlib.util.spec_from_file_location('fixture', PATH)
FIXTURE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FIXTURE)


class FixtureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = FIXTURE.ThreadingHTTPServer(('127.0.0.1', 0), FIXTURE.Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f'http://127.0.0.1:{cls.server.server_port}'

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def call(self, path, method='GET', data=None):
        request = Request(self.base+path, method=method,
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
        path += '&token='+FIXTURE.VENDOR_TOKEN
        point = self.call(path)[1]['items'][0]
        self.assertEqual(point['val'], 10.25)
        self.assertEqual(point['quality'], 'GOOD')
        self.assertEqual(self.call('/api/realdata?names=arbitrary&token='+FIXTURE.VENDOR_TOKEN)[0], 400)
        self.assertEqual(self.call('/api/realdata', 'POST', {'items': []})[0], 405)

    def test_fixture_control_is_bounded(self):
        self.assertEqual(self.call('/__fixture/control', 'POST', {'delayMs': 99999})[0], 400)
        self.assertEqual(self.call('/__fixture/control', 'POST', {'mode': 'missing'})[0], 200)
        value = self.call('/api/realdata?names=fixture.device.1.pressure&token='+FIXTURE.VENDOR_TOKEN)[1]
        self.assertIsNone(value['items'][0]['val'])
        self.call('/__fixture/control', 'POST', {'mode': 'normal'})


if __name__ == '__main__':
    unittest.main()
