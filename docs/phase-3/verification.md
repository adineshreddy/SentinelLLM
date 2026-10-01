# Phase 3 verification

Verified locally on October 1, 2026. Mock generation and read-only synthetic tools were used throughout. No NaviGator generation request was made. No provider credential was copied from PHIL.

## Results

| Suite | Passing checks |
|---|---:|
| Java gateway baseline/tool/schema checks | 57 |
| Real PostgreSQL/Redis Java integration checks | 16 |
| Python inspection | 12 |
| Python MCP adapter | 13 |
| Private/idempotent development setup | 2 |
| Docker Compose HTTP integration | 16 |
| **Total runtime checks** | **116** |

The final full HTTP integration run passed all 16 checks in 101.55 seconds. All three demo scripts passed against the restored healthy stack. PostgreSQL, Redis, gateway, inspection, and MCP adapter are healthy; the one-shot migration job completed successfully.

Commands:

```sh
tools/check.sh
python3 tools/check_state.py
SENTINEL_COMPOSE_TESTS=1 services/inspection/.venv/bin/python -m pytest -q tests/integration
python3 tools/demo.py
python3 tools/demo_phase2.py
python3 tools/demo_phase3.py
```

The default Java run skips the state-dependent class. `check_state.py` explicitly enables its 16 checks against newly created PostgreSQL/Redis containers, and removes those containers afterward. The total above counts each passing test once.

Contract verification separately passes 21 Draft 2020-12 schemas, 19 positive and 7 negative examples, 14 desired-behavior fixtures, OpenAPI 3.1, and local references. This does not measure detector accuracy or performance.

## Evidence

- **Version conflicts:** competing updates against the same revision produce one success and one HTTP 409. Policy and registry activate together. Old snapshots retain their original policy/version.
- **Live enforcement:** changing email handling to deny blocks the next request. A request already in flight continues using its captured revision. Restoring a policy produces a new revision rather than deleting history.
- **Registry changes:** disabling a tool and removing its permissions in the same revision updates discovery and blocks execution before dispatch. Remote schema references and foreign-tenant policies are rejected without activation.
- **Tenant isolation:** API traces/events cannot cross tenants. Direct restricted-role SQL returns no rows without tenant context, cannot read another context's rows, and rejects a cross-tenant insert. The runtime role cannot update/delete audit events or configuration history.
- **Durability:** committed approval/dispatch/outcome records survive a repository reconnect. HTTP operation traces remain identical across a gateway restart. Both Flyway migrations apply to a new database and validate/reapply safely against the existing development database.
- **Shared admission:** two independent Redis clients making 40 concurrent attempts admit exactly seven under a quota of seven. Revisions do not clear the counter. Concurrency leases are shared, release capacity, and isolate tenants.
- **Fail-closed dispatch/release:** a failed durable dispatch checkpoint results in zero provider calls. A failed output audit withholds the response even after the provider returns. Expired dispatches are shown as uncertain, with their stored evidence preserved.
- **Real outages:** stopping PostgreSQL or Redis returns HTTP 503 and leaves the real MCP execution counters unchanged. Recovery restores access. The existing inspection/MCP outage checks and unsafe result checks also pass with the new state backend.
- **Management roles:** application credentials cannot manage configuration; viewers cannot publish revisions; operators can update only their tenant. Unknown, duplicate, or invalid query parameters fail validation. Event/history pagination is bounded and exclusive.
- **Payload privacy:** PostgreSQL-backed runtime audit events validate against the whitelist schema and omit raw arguments, results, matched emails, and attack text. Generated setup credentials are distinct, private, and retained across reruns.
- **Schema interoperability:** the inspection service now tolerates unrelated schemas that omit optional `title` metadata; the regression check covers container/source contract loading.

Native Java checks use the installed Java 25 with a Java 21 target; the Docker runtime uses Temurin 21. Python services use Python 3.14. PostgreSQL 17.11 was observed in migration output; Redis runs the 7.2 image line. These are functional checks, not throughput benchmarks.

## Limits

The metadata log is append-only for the application role, not cryptographically tamper-proof. Database owners/administrators remain trusted. SQL/Redis volumes are local, without replication, tested backups, or disaster recovery. No retention/compaction service is implemented yet; configuration history and event storage grow over time.

Shared leases bound admission but do not provide distributed fencing or exactly-once execution. Requests are not automatically replayed after uncertain outcomes. Credentials are provisioned through trusted server configuration; rotation/management UI and browser authentication are later work.

The gateway still uses deterministic baseline content rules and constrained read-only MCP tools. Hosted NaviGator behavior remains unverified. The Phase 4 console, ML evaluation, observability/performance work, and Kubernetes/Terraform deployment remain subsequent phases.

The existing inspection TestClient/httpx deprecation warning and Mockito/JDK agent warnings remain non-failing development warnings.
