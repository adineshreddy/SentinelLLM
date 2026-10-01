# Phase 6 verification — October 1, 2026

**187 automated checks passed** across Java/Python/BFF unit checks, native database/Redis integration, local Compose integration, and browser workflows. Contract validation, monitoring rule tests/configuration checks, dashboard provisioning and the measured load run also passed. No hosted model calls were made.

| Check group | Passed | Evidence |
|---|---:|---|
| Java unit + real HTTP boundary | 68 | Adapter deadlines/interrupts, engine enforcement, tool security, independent metrics auth, finite labels, admission, audit instrumentation |
| Python inspector | 37 | Rules, UTF-8 spans, strict boundary, verified model, opt-in ML and failures |
| MCP bridge | 13 | SDK discovery/calls, schema pinning, bounded results and guarded fixtures |
| Credential setup | 4 | Private/idempotent files, separate identities, blank-template generation, monitoring not an application identity |
| Console BFF | 17 | Sessions, CSRF, roles, bounded routing and credential separation |
| Native PostgreSQL/Redis | 16 | Disposable real containers; tenant isolation, atomic revisions, immutable evidence, shared rates/leases and dependency failure |
| Existing Compose integration | 20 | Original gateway (4), tools/schema/MCP + persistent state + opt-in ML (16) |
| New monitoring/reliability Compose checks | 6 | Private scrape, Prometheus target, inspection outage, Redis outage, audit grant failure, concurrency overload, graceful drain |
| Console browser workflows | 5 | Real authenticated pages, policy history, operation traces, guarded agent demonstration and viewer restrictions |
| Grafana browser workflow | 1 | Separate login, provisioned panels, successful datasource queries, rendered charts and screenshot |

The reliability group has six test functions; one combines multiple auth/privacy/target assertions. The native state tests are skipped in the default unit run and were explicitly enabled with `tools/check_state.py`; they are counted only once here. The opt-in ML browser test and training/evaluation tests were not rerun in Phase 6; their earlier evidence remains in Phase 5. ML Compose enforcement checks were rerun and restored rule-only mode afterward.

## Commands and local logs

```sh
./tools/check.sh
python3 tools/check_state.py
SENTINEL_COMPOSE_TESTS=1 services/inspection/.venv/bin/python -m pytest -q tests/integration/test_compose.py
SENTINEL_COMPOSE_TESTS=1 services/inspection/.venv/bin/python -m pytest -q tests/integration/test_phase2.py tests/integration/test_phase3.py tests/integration/test_phase5.py
SENTINEL_COMPOSE_TESTS=1 services/inspection/.venv/bin/python -m pytest -q tests/integration/test_observability.py
(cd services/console && npx playwright test e2e/console.spec.ts)
(cd services/console && SENTINEL_MONITORING_BROWSER_TEST=1 npx playwright test e2e/monitoring.spec.ts)
/tmp/sentinellm-phase0-validation/bin/python tools/validate_contracts.py
docker compose --profile monitoring exec -T prometheus promtool check config /etc/prometheus/prometheus.yml
docker run --rm --entrypoint promtool -v "$PWD/infrastructure/monitoring:/rules:ro" -w /rules prom/prometheus:v3.13.3 test rules alerts.test.yml
python3 tools/load_test.py --requests 200 --concurrency 1 2 8 --output docs/phase-6/performance-v1.json
```

Private machine logs are in `/tmp/sentinel-phase6-{checks-final,state,integration,regression,reliability,console-browser,monitoring-browser,load}.log`. These are local execution evidence, not committed artifacts. Compose-mutating checks were run sequentially. Read-only Grafana verification and disposable native tests do not modify the shared application stack.

Promtool validated the scrape configuration and six alert expressions. Rule fixtures checked audit failure, dependency failure, gateway outage and exclusion of Redis policy/quota rejections from dependency failures. The actual Prometheus scrape target was healthy, Grafana's dashboard API returned all ten provisioned panels, and browser verification waited for datasource frames and rendered chart canvases. [Dashboard screenshot](grafana.png) was visually checked.

## Reliability findings and recovery

- Inspection and Redis outage requests returned sanitized 503 errors within the eight-second test bound and did not advance the provider dispatch counter. New requests recovered after dependency restart.
- Revoking only the runtime role's audit INSERT grant produced a failed operation with external state `none`, and no provider invocation. The grant was restored in `finally`, and a new request succeeded.
- With mock latency of one second, eight concurrent chat attempts admitted at most two provider calls. Remaining requests returned 429, with no queued provider dispatch.
- SIGTERM during a dispatched two-second mock call allowed its response and durable operation to complete before shutdown. Restarted management access confirmed status `completed` and external state `returned`.
- Repeated Grafana queries exposed an OOM at the initial 256-MiB limit after the load measurement. The final deployment uses 768 MiB and a 256-MiB Go memory target, with HTTP health checks and restart unless stopped. Browser verification was repeated after the change; the benchmark report retains its original measurement limits.
- An outbound HTTP interruption ended the worker promptly, preserved its interrupt flag and cancelled the unfinished future in `finally`; existing tests also cover deadlines across slow response bodies and body size limits.
- Repeated outage exercises initially exposed stale service-address recovery and strict immediate-recovery assertions. The final implementation sets positive/negative DNS cache lifetimes to five/one seconds and bounds Redis connect/reconnect/queues while disabling command replay. Final outage checks allow a ten-second recovery window for **new operations**, without retrying external execution. All six final reliability tests passed.

The benchmark completed 1,800 measured attempts, including intentional concurrency rejections, with no measured 5xx/transport failures. [Performance report](performance.md) discloses sample size, workload, machine, software, latency and resource boundaries. It does not claim production capacity or a Java/Go comparison.

Final local state: mock provider, hosted access disabled, classifier off, normal mock latency, restored audit grants, restored benchmark policy, running application services and monitoring. Existing PostgreSQL/Redis volumes and policy/event history were retained. See the [run guide](README.md) for limits including end-to-end cancellation, external uncertainty, lease fencing and readiness scope.
