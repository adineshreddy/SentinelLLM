# Phase 2 verification

The default stack uses mock generation and read-only synthetic tools. No NaviGator API request was made and no provider key was copied from PHIL. Docker builds use Java 21; native Java checks use the installed Java 25 with a Java 21 compilation target. Python services use Python 3.14.

## Automated checks

| Suite | Passing checks | Evidence |
|---|---:|---|
| Java gateway | 57 | Existing 42 baseline checks plus 15 tool/schema checks |
| Python inspection | 11 | Existing content, span, authentication, malformed-input, and container-path checks |
| Python MCP adapter | 13 | Real HTTP handshake/call; authentication/origin; schema drift; invalid content; JSON/body/destination bounds; container registry lookup |
| Docker Compose integration | 11 | Real gateway + inspector + MCP server; restart and synthetic-output failure fixtures |

**Total: 92 passing runtime checks.** The final full Compose run passed all 11 checks in 74.81 seconds. Both demonstration scripts passed against the restored healthy stack.

Commands:

```sh
tools/check.sh
SENTINEL_COMPOSE_TESTS=1 services/inspection/.venv/bin/python -m pytest -q tests/integration
python3 tools/demo_phase2.py
python3 tools/demo.py
```

The contract checker additionally passes 16 Draft 2020-12 schemas, 14 positive and 6 negative examples, 14 desired-behavior fixtures, OpenAPI 3.1, and local-reference/registry consistency. These checks are separate from runtime detection measurements.

## What the checks establish

- Unknown tools, unauthorized roles, spoofed request roles, prohibited ticket resources, extra arguments, and sensitive/injection arguments do not reach an executor. Compose checks compare the real server's execution counters before and after denied calls.
- Approved model function proposals are validated and released without execution. A separate execution request repeats authorization and argument inspection. Provider function aliases map to registered public/MCP IDs.
- Missing inspection or audit dependencies prevent execution. Unknown MCP outcomes produce `OUTCOME_UNCERTAIN`, with no retry or deferred execution after recovery.
- Results must satisfy the registered output schema. Injection results are blocked; PII is redacted. A redaction that invalidates the schema prevents completion/release.
- Structured model JSON is strictly parsed and schema checked. Duplicate properties, invalid types, unknown schema IDs, and malformed JSON fail closed. Decoded string inspection detects content hidden behind JSON Unicode escapes.
- Registry references and duplicates are rejected. MCP discovery must match the reviewed catalogue. Error results, images, conflicting text/structured results, non-object structured results, oversized responses, non-JSON responses, and unregistered transport destinations are rejected.
- The real local MCP session completes the SDK initialization, discovery, call, and cleanup flow with protocol `2025-11-25`. Host/origin validation and private authentication are active.
- Runtime audit events validate against the contract and contain metadata only. Tool decisions and uncertain outcomes are represented without argument, result, or matched text.

The first full Compose run passed nine checks and exposed an incorrect outage-test assumption: demonstration counters were expected to persist across an adapter restart. They are intentionally in memory. The test now asserts zero counters after recovery, demonstrating that no operation was queued or retried. The repaired test and the added runtime-audit test both passed before the final run.

## Practical limits

This is functional/security-boundary evidence for a constrained prototype, not an adversarial accuracy study or performance benchmark. Hosted NaviGator function-calling and structured-output behavior remain unverified. The generation mock deliberately produces repeatable proposals and valid support-answer JSON; configurable synthetic outputs exercise rejection paths.

Counters and limits reset on restart. The local JSONL audit is not transactional or tamper-proof. Tests cover read-only fixture tools; they do not establish safe execution of mutating or arbitrary external tools. Browser UI, Redis, PostgreSQL, cloud deployment, ML evaluation, and production MCP onboarding remain later phases.

The inspection test suite emits a dependency deprecation warning for its existing TestClient/httpx usage; checks pass. JVM tests emit existing Mockito/JDK agent warnings. Neither warning has been treated as a security verification claim.
