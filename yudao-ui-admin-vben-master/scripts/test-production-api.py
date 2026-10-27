#!/usr/bin/env python3
"""Authenticated real packaged API/database/adapter checks with explicit upstream fixture."""
import json
import os
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

if os.environ.get('CI_CONTRACT_FIXTURE') != 'explicit-test-only':
    raise SystemExit('Requires explicit-test-only fixture acknowledgement')
DIRECTORY = Path(os.environ.get('PRODUCTION_TEST_ARTIFACT_DIR', 'test-results/production-contract'))
DIRECTORY.mkdir(parents=True, exist_ok=True)
BASE = 'http://127.0.0.1:48083/admin-api/iot'
CHECKS = []


def http(url, method='GET', data=None, headers=None):
    body = None if data is None else json.dumps(data).encode()
    request = Request(url, data=body, method=method,
                      headers={'Content-Type': 'application/json', **(headers or {})})
    try:
        response = urlopen(request, timeout=15)
    except HTTPError as error:
        response = error
    with response:
        return response.status, json.loads(response.read())


def api(path, method='GET', data=None, token='ci-admin-tenant-7001', tenant=7001, expected=0, headers=None):
    h = {'tenant-id': str(tenant), **(headers or {})}
    if token:
        h['Authorization'] = 'Bearer ' + token
    status, result = http(BASE + path, method, data, h)
    assert result.get('code') == expected, (path, status, result, expected)
    return result.get('data')


def control(mode='normal', delay=0):
    status, result = http('http://127.0.0.1:48084/__fixture/control', 'POST', {'mode': mode, 'delayMs': delay})
    assert status == 200 and result.get('ok')


def check(name):
    CHECKS.append(name)
    print('PASS', name, flush=True)


def scene(device, name='API fixture verification'):
    return {'id': 0, 'name': name, 'description': 'TEST FIXTURE — not live plant acceptance',
            'version': 0, 'schemaVersion': 1, 'template': 'separation',
            'refreshSeconds': 5, 'staleAfterSeconds': 15,
            'bindings': [{'slotId': f'slot-{i}', 'deviceId': device if i == 1 else None,
                          'metricKeys': ['pressure'] if i == 1 else [],
                          'primaryMetricKey': 'pressure' if i == 1 else ''} for i in range(1, 5)]}


failure = None
try:
    deadline = time.monotonic() + 100
    while True:
        try:
            status, response = http(BASE + '/devices', headers={'tenant-id': '7001'})
            assert response.get('code') == 401, (status, response)
            break
        except (URLError, TimeoutError):
            if time.monotonic() >= deadline:
                raise RuntimeError('Packaged IoT process did not become ready within 100 seconds')
            time.sleep(1)
    api('/process-pages', token=None, expected=401)
    api('/process-pages', token='not-valid', expected=401)
    forged = quote(json.dumps({'id': 700101, 'userType': 2, 'tenantId': 7001}, separators=(',', ':')))
    api('/process-pages', token=None, expected=401, headers={'login-user': forged})
    api('/process-pages', token='ci-denied-tenant-7001', expected=403)
    api('/process-pages', tenant=7002, expected=403)
    check('real token, forged-header, method-permission and tenant-mismatch denials')

    assert api('/devices') == [], 'A newly provisioned test database must be empty'
    assert api('/process-pages') == []
    sources = api('/sources')
    assert len(sources) == 1 and sources[0]['status'] == 'CONFIGURED'
    assert sources[0]['enabled'] and sources[0]['configured'] and sources[0]['type'] == 'REALDB'
    for forbidden in ('token', 'password', 'baseUrl', 'username'):
        assert forbidden not in sources[0], 'Source metadata must not expose credentials/URL'
    check('empty database has no generated business data; source configuration metadata only')

    devices = []
    for i in range(1, 5):
        device = api('/devices', 'POST', {'name': f'自动化夹具设备{i}（非现场）', 'code': f'contract_fixture_{i}'})
        devices.append(device)
        api(f"/devices/{device['id']}/mappings", 'PUT', [
            {'metricKey': metric, 'name': label, 'pointName': f'fixture.device.{i}.{metric}', 'unit': unit}
            for metric, label, unit in [('pressure', '压力', 'MPa'), ('temperature', '温度', '°C')]
        ])
    (DIRECTORY / 'seed.json').write_text(json.dumps({'devices': devices}, ensure_ascii=False, indent=2))
    device_id = devices[0]['id']
    query = {'deviceIds': [device_id], 'metricKeys': ['pressure'], 'aggregation': 'RAW'}
    real = api('/query/realtime', 'POST', query)
    assert real['source'] == 'REALDB' and real['series'][0]['points'][0]['value'] == 10.25
    assert real['series'][0]['points'][0]['quality'] == 'GOOD'
    assert real['series'][0]['points'][0]['timeSource'] == 'DEVICE'
    end = datetime.now(timezone.utc)
    # The vendor protocol accepts milliseconds, matching browser Date.toISOString().
    # Python's default ISO serialization emits unsupported microseconds.
    history_query = {**query, 'startTime': (end-timedelta(minutes=5)).isoformat(timespec='milliseconds'),
                     'endTime': end.isoformat(timespec='milliseconds'), 'intervalMs': 60000}
    history = api('/query/history', 'POST', history_query)
    assert history['source'] == 'REALDB' and len(history['series'][0]['points']) == 6
    assert history['series'][0]['points'][2]['value'] is None
    assert history['series'][0]['points'][2]['quality'] == 'MISSING'
    check('real controller, PostgreSQL mapping and authenticated RealDbClient HTTP round trips')

    precision_before = http('http://127.0.0.1:48084/__fixture/state')[1]
    for boundary in ('startTime', 'endTime'):
        invalid_boundary = (datetime.fromisoformat(history_query[boundary])+timedelta(microseconds=1)).isoformat()
        invalid_query = {**history_query, boundary: invalid_boundary}
        api('/query/history', 'POST', invalid_query, expected=1070000001)
        api('/exports', 'POST', invalid_query, expected=1070000001)
    precision_after = http('http://127.0.0.1:48084/__fixture/state')[1]
    vendor_paths = lambda state: [r for r in state['requests'] if r['path'].startswith('/api/')]
    assert vendor_paths(precision_before) == vendor_paths(precision_after), 'Sub-millisecond input must fail before vendor access'
    assert api('/exports') == [], 'Invalid precision must not enqueue export jobs'
    check('sub-millisecond history/export boundaries rejected clearly before upstream or queueing')

    before = http('http://127.0.0.1:48084/__fixture/state')[1]
    reads = lambda state: sum(r['path'].startswith('/api/') for r in state['requests'])
    api('/query/realtime', 'POST', {**query, 'metricKeys': ['not_mapped']}, expected=1070000001)
    api('/query/realtime', 'POST', query, token='ci-admin-tenant-7002', tenant=7002, expected=1070000404)
    after = http('http://127.0.0.1:48084/__fixture/state')[1]
    assert reads(before) == reads(after), 'Unauthorized/unmapped points must not reach RealDB'
    check('unmapped and other-tenant device queries rejected before upstream access')

    saved = api('/process-pages', 'POST', scene(device_id))
    assert saved['id'] > 0 and saved['version'] >= 0
    assert api(f"/process-pages/{saved['id']}") == saved
    assert api('/process-pages', token='ci-admin-tenant-7002', tenant=7002) == []
    api(f"/process-pages/{saved['id']}", token='ci-admin-tenant-7002', tenant=7002, expected=1070000404)
    other = scene(device_id, 'cross tenant rejected')
    api('/process-pages', 'POST', other, token='ci-admin-tenant-7002', tenant=7002, expected=1070000404)
    changed = api(f"/process-pages/{saved['id']}", 'PUT', {**saved, 'name': 'updated fixture'})
    assert changed['version'] == saved['version'] + 1
    api(f"/process-pages/{saved['id']}", 'PUT', saved, expected=1070000409)
    api(f"/process-pages/{saved['id']}?version={saved['version']}", 'DELETE', expected=1070000409)
    invalid = scene(device_id)
    invalid['bindings'][0]['metricKeys'] = ['not_mapped']
    invalid['bindings'][0]['primaryMetricKey'] = 'not_mapped'
    api('/process-pages', 'POST', invalid, expected=1070000400)
    api(f"/process-pages/{changed['id']}?version={changed['version']}", 'DELETE')
    assert api('/process-pages') == []
    check('persisted process configuration, tenant isolation, mapped metric validation and optimistic versions')

    for mode in ('missing', 'stale', 'received'):
        control(mode)
        result = api('/query/realtime', 'POST', query)
        point = result['series'][0]['points'][0]
        if mode == 'missing':
            assert point['value'] is None and point['quality'] == 'MISSING'
        elif mode == 'stale':
            assert datetime.fromisoformat(point['time'].replace('Z', '+00:00')) < end-timedelta(minutes=9)
        else:
            assert point['timeSource'] == 'RECEIVED', 'Reception time must not masquerade as device sampling time'
    control('malformed')
    api('/query/realtime', 'POST', query, expected=1070000003)
    control('failure')
    status, failed = http(BASE+'/query/realtime', 'POST', query,
                          {'tenant-id': '7001', 'Authorization': 'Bearer ci-admin-tenant-7001'})
    assert failed.get('code') != 0 and failed.get('data') is None
    control()
    assert api('/query/realtime', 'POST', query)['series'][0]['points'][0]['value'] == 10.25
    check('missing/stale timestamps, malformed upstream failure, outage and recovery without fallback')
finally:
    import sys
    failure = repr(sys.exc_info()[1]) if sys.exc_info()[1] else None
    output = {'passed': failure is None, 'checks': CHECKS, 'failure': failure,
              'commit': os.environ.get('GITHUB_SHA'), 'evidenceType': 'AUTOMATED_CONTRACT_FIXTURE',
              'note': 'Actual packaged IoT/auth/tenant/permissions/PostgreSQL/RealDbClient; system and vendor peers are loopback test fixtures. Not actual Zijinqiao/site verification.'}
    (DIRECTORY/'production-api-verification.json').write_text(json.dumps(output, ensure_ascii=False, indent=2)+'\n')
