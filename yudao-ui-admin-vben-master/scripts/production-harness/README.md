# Production-path contract verification (test-only)

This host is **not a production application, login service, deployment or live
Zijinqiao demonstration**. Ordinary `build:micro`, `build:micro-demo` and deployed
frontend entrypoints do not include it. The isolated `production-contract` CI job
builds it explicitly and labels every screenshot `TEST FIXTURE / 非现场验收`.

The test runs the exact Java executable produced by the successful Java job,
real token/tenant/method-permission filters, actual IoT controllers/repositories,
disposable PostgreSQL, and separate `DefaultRealDbClient` instances. Only remote
system RPC and vendor HTTP are replaced with documented, loopback-only peers.
The browser mounts the actual production child through qiankun and the **same
bounded `createIotBridge` factory as the formal host**. Its transport is test-only;
this does not validate the formal login screen or the deployment gateway.

## Independent named sources

- Launch requires `CI_CONTRACT_FIXTURE=explicit-test-only`. Two **independent
  processes** bind only `127.0.0.1:48084` (`source-a`) and `127.0.0.1:48085`
  (`source-b`); each has its own disposable token, state, request audit and failure
  control. Only A supplies the allowlisted system RPC contract peer.
- Both sources expose the **same point names**, including
  `fixture.device.1.pressure`, but return distinguishable values `10.25` and
  `1010.25`. Each rejects the other's token. No fixture token accesses a real
  service, and URLs/tokens never enter evidence logs or browser metadata.
- The isolated service configuration explicitly allows tenant 7001 to use A/B
  and tenant 7002 to use B only. A third `source-disabled` entry has no listener
  and proves unavailable-source behavior. Legacy default is disabled, so missing
  source selection cannot fall back to the first named source.
- No `mock-enable`, trusted-login-user or tenant/security bypass is enabled.
  Invalid tokens, missing permissions, forged headers and tenant mismatches fail.
- API setup creates metadata using authenticated HTTP, not direct SQL inserts.
  All three schema migrations run only on a fresh job-local database.
- Only vendor read endpoints `/api/realdata` and `/api/hisdata` exist. No real
  device operations, upstream write APIs, deployment or persistent credentials.
- `CONFIGURED` means configuration readiness, never connection success.

## Coverage and evidence

The suite retains **all 7 original packaged API checks and 17 browser checks**,
then adds named-source checks. Minimum totals are **15 API + 23 browser checks**;
8 independent fixture-unit checks verify the peers themselves, not the app.
The unrelated frontend, demo, Java and PostgreSQL regressions remain required.

Additional packaged API coverage:

1. Explicit tenant-filtered source allowlists, no implicit source, immutable
   persisted device binding, request-source/point injection cannot reroute.
2. Equal device names and identical vendor points retain source/value identity in
   realtime and history. Metadata never exposes credentials or allowlists.
3. Source B outage, rejected authentication and bounded timeout leave source A's
   successful rows intact. B-only requests never route to A, including recovery.
4. Disabled sources fail explicitly, without contacting any alternate peer.
5. Observe a real worker `RUNNING` against A, enqueue a cross-source job and
   confirm it remains **QUEUED** while B's point mapping/unit change. Downloaded
   CSV retains the original mapping/unit/source snapshot; a fresh query uses the
   changed mapping. `sourceId` and `deviceId` disambiguate every CSV row.
6. Partial-source exports fail closed with no downloadable incomplete artifact;
   queued cancellation stays terminal and other tenants cannot read the job.
7. Independent audits prove authenticated GET-only traffic to the proper peers.

Additional browser coverage:

- Required explicit source selector, disabled-source option, actual device
  creation/persistence and same-name device/source labels.
- Cross-source process binding save/reload, distinct values and history labels;
  disabled/outage states preserve the healthy source without invented values.
- Cross-source history retains healthy rows with a labelled partial error;
  recovered rows keep their identities. CSV downloads show source summaries.
- Cancel, tenant replacement and unmount/remount discard old multi-source
  requests, selections and late results; original polling/route guards remain.

`production-*-verification.json`, redacted per-peer audits, CSVs, screenshots and
traces identify the checked-out SHA, tested path, fixture boundary and failures.
Screenshots retain the visible fixture banner, including the added cross-source
process/history evidence. **No result is actual Zijinqiao/site acceptance.**

Individual local checks (no browser or production services):

```bash
python3 scripts/test-production-fixture.py
python3 -m py_compile scripts/production-fixture.py scripts/test-production-api.py
node --check scripts/test-production-pages.mjs
pnpm exec tsc -p scripts/production-harness/tsconfig.json
pnpm exec vite build --config scripts/production-harness/vite.config.ts
```

The actual packaged API/browser suite runs in
`.github/workflows/new-data-center-verify.yml` using fresh PostgreSQL, the tested
executable, loopback fixtures and runner Chrome. A sandbox-blocked local browser
must not be worked around by weakening its sandbox; use the authorized CI path.
