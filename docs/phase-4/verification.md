# Phase 4 verification

Verified locally on October 1, 2026. Default mock provider; no hosted LLM calls. Final Docker services are healthy.

## Automated checks

| Check | Passed | Evidence |
|---|---:|---|
| Java gateway unit/HTTP tests | 57 | `tools/check.sh`; content/tool/schema/policy authority remains intact |
| Python inspector | 12 | Rule detection, UTF-8 spans, bounds and fail-closed behavior |
| Python MCP adapter | 13 | SDK protocol boundary, catalogue validation and bounded execution |
| Credential setup | 3 | Private/idempotent provisioning, preserved provider values, separate nonempty console passwords |
| Console BFF security | 17 | HTTP tests for roles, sessions, expiry, CSRF/Origin/Host, admission, proxy allowlist, bounds, error sanitization and support orchestration |
| Real-browser console tests | 5 | Playwright against Docker gateway, PostgreSQL, Redis, inspector and MCP |
| Full backend Compose integration | 16 | Content/tool/output validation, zero executions for denied tools, dependency outages/recovery, persistence, tenant isolation and revision conflicts |
| **Total** | **123** | All final executions passed |

The separate 16-test native PostgreSQL/Redis suite was verified in Phase 3 and was not rerun here; it is not included in this total. The standard Java run skips that opt-in suite. Real durable-state behavior was exercised again through Compose and browser tests.

## Browser evidence

The final five-test browser run passed in 5.9 seconds. It verified:

- Synthetic inspection seeding, injection preview and enforced denial, PII redaction, poisoned-RAG denial, forbidden-tool denial, and operation traces.
- Filtered event results, actual MCP execution, three independent guarded operations, and schema-validated structured answers.
- Viewer-disabled UI controls plus direct server rejection of writes and execution; viewer policy/event reads.
- PII policy publication changes the real inspector decision; stale revision writes receive 409; reset retains history. Original policy semantics are restored as a new revision in test cleanup.
- Desktop/login screens and 390-pixel mobile layouts without horizontal document overflow.
- Real form login rejection/success, HttpOnly cookie visibility, empty browser credential storage, and logout invalidation.
- No JavaScript page errors in the main walkthrough; no configured keys/passwords in the rendered demo text.

Screenshots: [overview](overview.png), [blocked operation](blocked-trace.png), [policy editor](policies.png), [support agent](support-agent.png), [login](login.png), [mobile](mobile.png). These contain synthetic fixtures and audit metadata.

Early browser runs identified decorative navigation icons and implicit select labels interfering with accessible names; these were corrected. An initial backend integration run overlapped a console rebuild and had two Compose recovery failures. The final sequential backend run passed all 16 tests in 110.21 seconds. Run rebuilding/outage suites sequentially against this shared local stack.

## Build and boundary checks

- TypeScript type checking, Vite production build, and Prettier checks pass. Frontend JS is about 246 kB before compression, about 76 kB gzip; CSS about 13 kB.
- Lockfile includes exact React/Vite/TypeScript/Playwright versions; `npm audit` reported zero known vulnerabilities at verification time.
- Docker console runs as the unprivileged Node user, read-only filesystem, dropped capabilities, loopback host binding, 192 MiB limit, and a working health probe.
- `.env` retains mode 0600. Configured private credentials were checked against all client build files and were absent. Credentials are not included in client build variables or browser storage.
- Offline contracts pass: 21 schemas, 19 valid/7 invalid examples, 14 desired-behavior fixtures, local references and OpenAPI consistency.
- Console CLI workflow and synthetic seed commands pass; no credentials or raw provider failures are printed.

Reproduce:

```sh
npm --prefix services/console ci
tools/check.sh
SENTINEL_COMPOSE_TESTS=1 services/inspection/.venv/bin/python -m pytest tests/integration -q
# Wait for shared quotas if you have just run many demos. Keep Compose rebuilds separate.
npm --prefix services/console run test:e2e
python3 tools/demo_phase4.py
python3 tools/demo_phase4.py --seed
```

Playwright requires its Chromium installation (`cd services/console && npx playwright install chromium`). No browser trace/video recording is enabled because login credentials are private.

## Scope

This is a locally authenticated, single-instance demo console, not a production public identity service. Sessions are in-memory and expire after 30 minutes. Generation/tool demos require mock mode; live NaviGator, optional Ollama, ML evaluation, performance measurements, monitoring, Kubernetes, Terraform and CI are outside this phase. Rule-based fixtures demonstrate enforcement boundaries without establishing general detection accuracy.
