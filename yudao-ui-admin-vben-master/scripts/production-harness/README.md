# Production-path contract verification (test-only)

This host is **not a production application, login service, deployment or live
Zijinqiao demonstration**. Ordinary `build:micro`, `build:micro-demo` and deployed
frontend entrypoints do not include it. The isolated `production-contract` CI job
builds it explicitly and labels every screenshot `TEST FIXTURE / 非现场验收`.

The test runs the exact Java executable produced by the successful Java job,
real token/tenant/method-permission filters, actual IoT controllers/repositories,
disposable PostgreSQL, and `DefaultRealDbClient`. Only remote system RPC and
vendor HTTP are replaced with the documented, loopback-only contract fixture.
The browser mounts the actual production child through qiankun and the **same
bounded `createIotBridge` factory as the formal host**. Its transport is test-only;
this does not validate the formal login screen or the deployment gateway.

- Fixture launch requires `CI_CONTRACT_FIXTURE=explicit-test-only` and binds only
  `127.0.0.1:48084`. Static fixture tokens cannot access any real service.
- No `mock-enable`, trusted-login-user or tenant/security bypass is enabled.
  The fixture permission peer is allowlisted, not allow-all. Invalid tokens,
  missing permissions, forged headers and tenant mismatches must fail.
- API setup creates platform metadata using authenticated HTTP, not direct SQL
  inserts. SQL initialization is restricted to a fresh job-local database.
- Only the documented read endpoints `/api/realdata` and `/api/hisdata` exist.
  No real device operations, upstream write APIs, deployment or credentials.
- Source `CONFIGURED` means configuration readiness, never connection success.
- `production-*-verification.json`, screenshots and traces identify the exact
  checked-out SHA, tested path, fixture boundary and any failure. No screenshot
  or CI result is presented as actual Zijinqiao/site acceptance.

Individual local checks (without browser or production services):

```bash
python3 scripts/test-production-fixture.py
python3 -m py_compile scripts/production-fixture.py scripts/test-production-api.py
pnpm exec tsc -p scripts/production-harness/tsconfig.json
pnpm exec vite build --config scripts/production-harness/vite.config.ts
```

The real API/browser suite is run by `.github/workflows/new-data-center-verify.yml`
using a fresh isolated database, the tested executable and runner Chrome. It
retains the independent pre-existing demo, Java and PostgreSQL regressions.
