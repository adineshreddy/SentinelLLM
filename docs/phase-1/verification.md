# Phase 1 verification

**Date:** September 30, 2026  
**Mode:** Local mock provider only; no hosted LLM calls  
**Result:** 42 Java tests, 11 Python tests, and 4 Compose integration tests passed

## Checks performed

| Check | Evidence |
|---|---|
| Java engine | 22 tests covering policy enforcement, forwarding isolation, redaction, RAG, output denial, audit failures, inspection failures, Unicode/overlap, rate limits, concurrency, and startup snapshot immutability |
| Java adapters | 15 tests covering URL normalization/rejection, trusted provider mapping, explicit hosted opt-in/key requirements, actual local HTTP headers/payloads, response byte bounds, and slow-body deadlines |
| Java HTTP boundary | 5 tests through a real Spring HTTP server: authentication before parsing, success contract, invalid/spoofed input, known-length/chunked body bounds, partial inspection failure |
| Python inspection | 11 tests covering service auth, malformed/duplicate/non-finite/invalid UTF-8 input, selected content fixtures, byte spans, no matched-value leakage, overflow, request bounds, and container path regression |
| Compose | 4 tests using both real services: Unicode preview/RAG denial, inspection outage/recovery, output secret denial, output PII redaction |
| Demonstration script | Benign request 200; injection 403; synthetic credential marker 403; input PII redaction 200; poisoned RAG 403; missing authentication 401 |
| Container startup | Both non-root containers healthy; gateway bound to loopback port 8080; inspection port not published |
| Contracts | 15 schemas, 13 valid/6 invalid examples, 14 desired-behavior fixtures, and OpenAPI 3.1 validation |

## Reproduction

```sh
tools/check.sh
docker compose up -d --build --wait
python3 tools/demo.py
SENTINEL_COMPOSE_TESTS=1 services/inspection/.venv/bin/python -m pytest -q tests/integration/test_compose.py
```

Install the pinned Python development environment as described in the [run guide](README.md) first. Generated private credentials stay in this project's `.env`; output never needs to display them. The mock output overrides used by integration tests are restored afterward.

The first image startup revealed an eagerly evaluated source-directory fallback. A container-path regression test now verifies the configured schema directory takes precedence. The corrected Compose deployment passed startup and end-to-end checks.

## Limits of this evidence

- No live NaviGator request, account permission check, quota verification, or free-allowance confirmation was performed.
- Fixture success is not a held-out accuracy evaluation, universal injection defense, or PII coverage guarantee.
- No throughput or latency improvement claim is made. Benchmarks are Phase 6.
- Local limits reset on restart and do not coordinate multiple replicas.
- Audit is local metadata JSONL, not PostgreSQL transactions, an immutable ledger, or a complete authentication-event stream.
- Tool execution, MCP negotiation, structured-output validation, multi-tenant persistence, and the web console are not implemented in Phase 1.
- Test dependencies emit a Starlette/httpx deprecation notice and a Mockito dynamic-agent notice under Java 25. They do not fail the tests; future dependency maintenance must address them.

Machine validation used Java 25 with a Java 21 compilation target, Python 3.14.2, and Docker 29.6.1. The Java container runs Temurin 21. Library versions and Python dependency locks are recorded in the service build files.
