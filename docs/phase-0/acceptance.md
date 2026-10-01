# Acceptance criteria and implementation backlog

These are required observable behaviors, not claims that tests already pass against an application. Contract-only validation is separate.

## Acceptance matrix

| ID | Behavior / proof | First phase |
|---|---|---|
| A01 | Missing/invalid credential returns 401; no inspection/provider/tool call | 1 |
| A02 | Injection fixture denied with 403 and zero provider calls | 1 |
| A03 | Synthetic credential marker denied before provider; no credential in events/errors | 1 |
| A04 | Malicious RAG chunk denied; assembled payload never reaches provider | 1 |
| A05 | Inspection timeout, invalid spans, or partial failure returns 503 with no protected release | 1 |
| A06 | Synthetic email is absent from forwarded prompt; redaction marker present | 1 |
| A07 | Mock output containing selected PII is redacted; prohibited secret output blocked | 1 |
| A08 | Disallowed tool proposal/execution produces denial; invocation counter unchanged | 2 |
| A09 | Unknown tool, unexpected argument, or invalid resource is denied before invocation | 2 |
| A10 | Spoofed tenant/role/policy fields fail request validation; identity headers confer no authority | 1 |
| A11 | Arbitrary provider/tool URL and remote schema reference cause zero unexpected network calls | 1/2 |
| A12 | Validation errors, upstream exceptions, audit and metrics contain no secret/raw payload | 1 |
| A13 | Malformed/schema-invalid model JSON returns sanitized error, no raw output | 2 |
| A14 | Tool metadata/result injection blocked; unsupported MCP content/method rejected | 2 |
| A15 | Cross-tenant query/update denied and management permissions enforced | 3 |
| A16 | Parallel requests respect configured fixed-window budget; Redis failure blocks in durable mode | 1 local / 3 shared |
| A17 | Oversized/chunked bodies, provider responses, findings, and concurrency rejected within bounds | 1 |
| A18 | Unicode and overlapping redactions preserve unaffected text; invalid UTF-8 boundaries deny | 1 |
| A19 | Mandatory pre-operation audit failure prevents forwarding/execution | 1 local / 3 durable |
| A20 | Tool timeout/uncertain outcome does not automatically trigger a second execution | 2 |
| A21 | One operation uses one policy snapshot; update conflict rejected; execution re-authorizes | 1 snapshot / 3 updates |
| A22 | Mock runs without key; NaviGator path/model/key mapping verified by fake upstream | 1 |
| A23 | Playground/UI displays actual backend events; mock/live mode explicit | 4 |
| A24 | Held-out evaluation reports misses, false positives, versions, data provenance, and latency | 5 |
| A25 | Load tests separate gateway, detector, and generation time; report tail latency/CPU/RAM | 6 |
| A26 | Local Terraform apply/update/destroy and recovery steps reproduce documented deployment | 7 |

JSON fixtures in `evaluation/fixtures/phase0-cases.json` provide starter cases. Add paraphrases and benign near-misses when detectors exist. Desired labels are not ground-truth accuracy measurements.

## Phase 1 implementation backlog

| Order | Work item | Exit condition |
|---|---|---|
| 1 | Pin compatible Java/Spring/Python releases; scaffold services and local build commands | Both services start locally; no live calls |
| 2 | Implement mock provider and test recording of calls | Exact forwarded payload observable to tests |
| 3 | Add credential-to-identity configuration and request limits | A01, A10, A17 |
| 4 | Implement internal findings contract and rule detectors | Valid findings, no matched-value leakage |
| 5 | Implement policy load/snapshot and UTF-8 redaction | A02, A03, A06, A18, A21 |
| 6 | Assemble/inspect RAG and final provider request | A04; labels never grant authority |
| 7 | Buffer/inspect provider responses, write local audit | A05, A07, A12, A19 |
| 8 | Add single-process limits/deadlines and sanitized errors | A16, A17; document deployment limitation |
| 9 | Implement configurable NaviGator adapter against fake upstream | A11, A22; no hosted test required |
| 10 | Package Compose core profile and API demo commands | Clean-start benign, deny, and redaction scenarios |

First demonstrable slice: client → authenticated Java gateway → Python inspection → mock provider → output inspection → client. After this passes, an opt-in NaviGator smoke test can verify the live adapter within confirmed allowance.

## Phase 0 verification scope

The contract checker validates schema syntax, sample payloads, intentional negative cases, local references, policy completeness, registry/fixture consistency, and OpenAPI 3.1 validity. It cannot test actual enforcement, provider compatibility, SDK negotiation, detector accuracy, or frontend behavior.

No fixed implementation dates or fabricated performance/accuracy targets are assigned. Subsequent work should report completed acceptance IDs and concrete evidence.
