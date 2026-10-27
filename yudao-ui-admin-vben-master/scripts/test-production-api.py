#!/usr/bin/env python3
"""Authenticated real packaged API/database/adapter checks with explicit upstream fixture."""
import csv
import io
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
SOURCES = {'source-a': 48084, 'source-b': 48085}


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


def control(mode='normal', delay=0, source='source-a', history_delay=0):
    status, result = http(f'http://127.0.0.1:{SOURCES[source]}/__fixture/control', 'POST',
                          {'mode': mode, 'delayMs': delay, 'historyDelayMs': history_delay})
    assert status == 200 and result.get('ok')


def state(source):
    return http(f'http://127.0.0.1:{SOURCES[source]}/__fixture/state')[1]


def vendor_reads(source, path=None):
    return [entry for entry in state(source)['requests']
            if entry['path'].startswith('/api/') and (path is None or entry['path'] == path)]


def deny(path, method='POST', data=None, token='ci-admin-tenant-7001', tenant=7001):
    status, result = http(BASE + path, method, data,
                          {'tenant-id': str(tenant), 'Authorization': 'Bearer ' + token})
    assert result.get('code') not in (None, 0), (path, status, result)
    assert result.get('data') is None, (path, result)
    return result


def wait_for(test, description, seconds=35):
    deadline = time.monotonic() + seconds
    while True:
        value = test()
        if value:
            return value
        assert time.monotonic() < deadline, description
        time.sleep(0.05)


def export_done(job):
    latest = api(f"/exports/{job['id']}")
    return latest if latest['status'] in ('SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED') else None


def export_csv(job):
    request = Request(BASE + f"/exports/{job['id']}/file",
                      headers={'tenant-id': '7001', 'Authorization': 'Bearer ci-admin-tenant-7001'})
    with urlopen(request, timeout=15) as response:
        assert response.status == 200 and 'text/csv' in response.headers['Content-Type']
        assert response.headers['Cache-Control'] == 'no-store'
        return response.read().decode('utf-8-sig')


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
    by_source = {source['id']: source for source in sources}
    assert set(by_source) == {'source-a', 'source-b', 'source-disabled'}, by_source
    for source_id in ('source-a', 'source-b'):
        source = by_source[source_id]
        assert source['status'] == 'CONFIGURED' and source['enabled'] and source['configured']
        assert source['type'] == 'REALDB'
    assert by_source['source-disabled']['status'] == 'DISABLED'
    for source in sources:
        for forbidden in ('token', 'password', 'baseUrl', 'username', 'allowedTenants'):
            assert forbidden not in source, 'Source metadata must not expose credentials/URL/allowlist'
    assert [source['id'] for source in api('/sources', token='ci-admin-tenant-7002', tenant=7002)] == ['source-b']
    check('empty database has no generated business data; source configuration metadata only')

    devices = []
    for i in range(1, 5):
        device = api('/devices', 'POST', {'name': f'自动化夹具设备{i}（非现场）', 'code': f'contract_fixture_{i}', 'sourceId': 'source-a'})
        devices.append(device)
        api(f"/devices/{device['id']}/mappings", 'PUT', [
            {'metricKey': metric, 'name': label, 'pointName': f'fixture.device.{i}.{metric}', 'unit': unit}
            for metric, label, unit in [('pressure', '压力', 'MPa'), ('temperature', '温度', '°C')]
        ])
    peer = api('/devices', 'POST', {'name': devices[0]['name'], 'code': 'contract_fixture_b', 'sourceId': 'source-b'})
    peer_mappings = api(f"/devices/{peer['id']}/mappings", 'PUT', api(f"/devices/{devices[0]['id']}/mappings"))
    unavailable = api('/devices', 'POST', {'name': '禁用源夹具（非现场）', 'code': 'contract_fixture_disabled', 'sourceId': 'source-disabled'})
    api(f"/devices/{unavailable['id']}/mappings", 'PUT', peer_mappings)
    (DIRECTORY / 'seed.json').write_text(json.dumps({'devices': devices, 'peer': peer, 'unavailable': unavailable, 'sources': sources}, ensure_ascii=False, indent=2))
    device_id = devices[0]['id']
    query = {'deviceIds': [device_id], 'metricKeys': ['pressure'], 'aggregation': 'RAW'}
    real = api('/query/realtime', 'POST', query)
    assert real['source'] == 'REALDB' and real['series'][0]['points'][0]['value'] == 10.25
    assert real['series'][0]['sourceId'] == 'source-a' and not real['partial'] and real['errors'] == []
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
    malformed = deny('/query/realtime', data=query)
    assert malformed['code'] == 1070000002 and 'source-a=SOURCE_QUERY_FAILED' in malformed['msg']
    control('failure')
    status, failed = http(BASE+'/query/realtime', 'POST', query,
                          {'tenant-id': '7001', 'Authorization': 'Bearer ci-admin-tenant-7001'})
    assert failed.get('code') != 0 and failed.get('data') is None
    control()
    assert api('/query/realtime', 'POST', query)['series'][0]['points'][0]['value'] == 10.25
    check('missing/stale timestamps, malformed upstream failure, outage and recovery without fallback')

    # A persisted device can never route by request-supplied source or point names.
    before = {source: len(vendor_reads(source)) for source in SOURCES}
    for invalid in ({'name': 'omitted source', 'code': 'missing_source'},
                    {'name': 'unknown source', 'code': 'unknown_source', 'sourceId': 'no-such-source'}):
        deny('/devices', data=invalid)
    deny('/devices', data={'name': 'tenant forbidden source', 'code': 'denied_source', 'sourceId': 'source-a'},
         token='ci-admin-tenant-7002', tenant=7002)
    allowed = api('/devices', 'POST', {'name': '租户隔离专用（非现场）', 'code': 'tenant_two_b', 'sourceId': 'source-b'},
                  token='ci-admin-tenant-7002', tenant=7002)
    assert allowed['sourceId'] == 'source-b'
    deny(f"/devices/{device_id}", method='PUT', data={**devices[0], 'sourceId': 'source-b'})
    persisted = {device['id']: device for device in api('/devices')}
    assert persisted[device_id]['sourceId'] == 'source-a' and persisted[peer['id']]['sourceId'] == 'source-b'
    assert devices[0]['name'] == peer['name'] and devices[0]['id'] != peer['id']
    assert before == {source: len(vendor_reads(source)) for source in SOURCES}
    injected = api('/query/realtime', 'POST', {**query, 'sourceId': 'source-b', 'pointName': 'fixture.device.4.pressure'})
    assert injected['series'][0]['sourceId'] == 'source-a' and injected['series'][0]['points'][0]['value'] == 10.25
    check('explicit tenant source allowances, immutable persisted bindings and no implicit first-source fallback')

    cross_query = {**query, 'deviceIds': [device_id, peer['id']]}
    cross_history = {**history_query, 'deviceIds': [device_id, peer['id']]}
    for endpoint, request in (('/query/realtime', cross_query), ('/query/history', cross_history)):
        result = api(endpoint, 'POST', request)
        assert not result['partial'] and result['errors'] == []
        series = {entry['sourceId']: entry for entry in result['series']}
        assert set(series) == {'source-a', 'source-b'}
        assert series['source-a']['deviceId'] == device_id and series['source-b']['deviceId'] == peer['id']
        assert series['source-a']['points'][0]['value'] == 10.25
        assert series['source-b']['points'][0]['value'] == 1010.25
    # Both peers saw the identical raw point, each through its own valid token.
    for source in SOURCES:
        assert any(entry.get('names') == ['fixture.device.1.pressure'] for entry in vendor_reads(source))
        assert all(entry.get('authenticated') for entry in vendor_reads(source))
    check('same-named devices and identical vendor point strings retain independent source/value/history identity')

    peer_query = {**query, 'deviceIds': [peer['id']]}
    login_events = lambda source: [entry for entry in state(source)['requests'] if entry['path'] == '/api/userlogin']
    assert login_events('source-a') == [] and login_events('source-b') == [], 'Initial static fixture sessions need no login'
    for mode, delay in (('failure', 0), ('unauthorized', 0), ('normal', 5000)):
        control(mode, delay, source='source-b')
        before_a_logins = len(login_events('source-a'))
        before_a = len(vendor_reads('source-a'))
        deny('/query/realtime', data=peer_query)
        assert len(vendor_reads('source-a')) == before_a, 'A failed B-only request must never route to source A'
        start = time.monotonic()
        for endpoint, request in (('/query/realtime', cross_query), ('/query/history', cross_history)):
            result = api(endpoint, 'POST', request)
            assert result['partial'] is True and len(result['errors']) == 1
            assert result['errors'][0]['sourceId'] == 'source-b'
            assert isinstance(result['errors'][0]['code'], str) and result['errors'][0]['message']
            assert len(result['series']) == 1 and result['series'][0]['sourceId'] == 'source-a'
            assert result['series'][0]['points'][0]['value'] == 10.25
            serialized = json.dumps(result)
            assert 'token=' not in serialized and '127.0.0.1' not in serialized
        assert time.monotonic() - start < 14, 'Failed source must have bounded wait, not obscure healthy source'
        before_recovery = len(login_events('source-b'))
        control(source='source-b')
        assert api('/query/realtime', 'POST', peer_query)['series'][0]['points'][0]['value'] == 1010.25
        assert len(login_events('source-a')) == before_a_logins, 'B auth failure must not invalidate A session'
        if mode == 'unauthorized':
            attempts = login_events('source-b')
            assert len(attempts) == before_recovery + 1, 'Next explicit request must authenticate B again exactly once'
            assert attempts[-1]['authenticated'] and attempts[-1]['authorized']
            assert any(entry['authenticated'] and not entry['authorized'] for entry in attempts[:-1]), 'Failure mode must reject even valid B credentials'
        else:
            assert len(login_events('source-b')) == before_recovery, 'Outage/timeout must not gratuitously replace valid sessions'
    check('per-source outage, rejected authentication and timeout preserve healthy results without cross-source fallback')

    unavailable_query = {**query, 'deviceIds': [unavailable['id']]}
    before = {source: len(vendor_reads(source)) for source in SOURCES}
    deny('/query/realtime', data=unavailable_query)
    assert before == {source: len(vendor_reads(source)) for source in SOURCES}
    partial = api('/query/realtime', 'POST', {**query, 'deviceIds': [device_id, unavailable['id']]})
    assert partial['partial'] and partial['errors'][0]['sourceId'] == 'source-disabled'
    assert partial['series'][0]['sourceId'] == 'source-a' and partial['series'][0]['points'][0]['value'] == 10.25
    check('disabled source is explicit unavailable metadata/query failure, never a fallback or healthy-source loss')

    # Hold the single real worker in an upstream history request; then observe a
    # genuinely QUEUED job before editing its mapping via the authenticated API.
    control(history_delay=3000)
    before_history = len(vendor_reads('source-a', '/api/hisdata'))
    blocker = api('/exports', 'POST', history_query)
    wait_for(lambda: len(vendor_reads('source-a', '/api/hisdata')) > before_history
             and api(f"/exports/{blocker['id']}")['status'] == 'RUNNING', 'blocker worker reaches real upstream')
    queued = api('/exports', 'POST', cross_history)
    assert queued['status'] == 'QUEUED' and set(queued['sourceIds']) == {'source-a', 'source-b'}
    changed_mappings = [{**mapping, 'pointName': 'fixture.device.4.pressure', 'unit': 'CHANGED'}
                        if mapping['metricKey'] == 'pressure' else mapping for mapping in peer_mappings]
    try:
        api(f"/devices/{peer['id']}/mappings", 'PUT', changed_mappings)
        assert api(f"/exports/{queued['id']}")['status'] == 'QUEUED', 'Mapping must change while target job is actually queued'
        control()
        done = wait_for(lambda: export_done(queued), 'snapshot export completes')
        assert done['status'] == 'SUCCEEDED', done
        rows = list(csv.DictReader(io.StringIO(export_csv(done))))
        assert set(rows[0]) == {'sourceId', 'deviceId', 'metricKey', 'unit', 'time', 'value', 'quality'}
        for source, expected_device, value in (('source-a', device_id, '10.25'), ('source-b', peer['id'], '1010.25')):
            group = [row for row in rows if row['sourceId'] == source]
            assert len(group) == 6 and {row['deviceId'] for row in group} == {str(expected_device)}
            assert group[0]['value'] == value and {row['unit'] for row in group} == {'MPa'}
        assert 'CHANGED' not in export_csv(done) and '1040.25' not in export_csv(done)
        (DIRECTORY / 'cross-source-snapshot-history.csv').write_text(export_csv(done))
        # Current direct query changes, proving export did not merely reread current mappings.
        current = api('/query/realtime', 'POST', peer_query)['series'][0]
        assert current['points'][0]['value'] == 1040.25 and current['unit'] == 'CHANGED'
    finally:
        api(f"/devices/{peer['id']}/mappings", 'PUT', peer_mappings)
        control()
    assert wait_for(lambda: export_done(blocker), 'blocker completes')['status'] == 'SUCCEEDED'
    check('queued cross-source export persists original mapping/source/unit snapshot and unambiguous CSV identity')

    control('failure', source='source-b')
    failed_export = api('/exports', 'POST', cross_history)
    failed_export = wait_for(lambda: export_done(failed_export), 'partial-source export fails closed')
    assert failed_export['status'] == 'FAILED' and failed_export['error']
    deny(f"/exports/{failed_export['id']}/file", method='GET')
    control(source='source-b')
    check('partial upstream failure never produces a silently incomplete successful export or downloadable CSV')

    control(history_delay=3000)
    blocker = api('/exports', 'POST', history_query)
    wait_for(lambda: api(f"/exports/{blocker['id']}")['status'] == 'RUNNING', 'cancellation blocker runs')
    cancelled = api('/exports', 'POST', cross_history)
    assert cancelled['status'] == 'QUEUED'
    cancelled = api(f"/exports/{cancelled['id']}/cancel", 'POST')
    assert cancelled['status'] == 'CANCELLED'
    control()
    wait_for(lambda: export_done(blocker), 'cancellation blocker completes')
    time.sleep(2.5)  # Beyond worker poll interval: cancellation must remain terminal.
    assert api(f"/exports/{cancelled['id']}")['status'] == 'CANCELLED'
    deny(f"/exports/{cancelled['id']}/file", method='GET')
    api(f"/exports/{queued['id']}", token='ci-admin-tenant-7002', tenant=7002, expected=1070000404)
    check('queued export cancellation stays terminal and cross-tenant job/file identity remains protected')

    audits = {source: state(source) for source in SOURCES}
    for source, audit in audits.items():
        vendor = [entry for entry in audit['requests'] if entry['path'].startswith('/api/')]
        assert vendor and all(entry['method'] == 'GET' and entry['authenticated'] for entry in vendor)
        assert all(entry['sourceId'] == source for entry in vendor)
    check('independent peer audits contain only their own authenticated read traffic and no vendor mutations')
    (DIRECTORY / 'multi-source-api-audit.json').write_text(json.dumps(audits, ensure_ascii=False, indent=2) + '\n')
    assert len(CHECKS) >= 15, 'Existing checks must never be reduced when adding multi-source coverage'

finally:
    import sys
    failure = repr(sys.exc_info()[1]) if sys.exc_info()[1] else None
    # Preserve redacted peer evidence even if a later API assertion fails.
    try:
        audits = {source: state(source) for source in SOURCES}
        (DIRECTORY / 'multi-source-api-audit.json').write_text(json.dumps(audits, ensure_ascii=False, indent=2) + '\n')
    except Exception:
        pass
    for source in SOURCES:
        try:
            control(source=source)
        except Exception:
            pass
    output = {'passed': failure is None, 'checks': CHECKS, 'failure': failure,
              'commit': os.environ.get('GITHUB_SHA'), 'evidenceType': 'AUTOMATED_CONTRACT_FIXTURE',
              'note': 'Actual packaged IoT/auth/tenant/permissions/PostgreSQL/RealDbClient with independent named-source fixtures and tokens; system and vendor peers are loopback test fixtures. Not actual Zijinqiao/site verification.'}
    (DIRECTORY/'production-api-verification.json').write_text(json.dumps(output, ensure_ascii=False, indent=2)+'\n')
