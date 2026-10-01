# Threat model

## Assets and attacker control

Assets: provider/application credentials, permitted tool capabilities, tenant policies, private context, model output, quota, audit integrity, and service availability.

An attacker can submit prompt/context strings, spoof body/header fields, craft historical messages, influence retrieved documents, and cause malicious model/tool outputs. A tool server may be compromised. An attacker is not assumed to control the gateway host, trusted operator credentials, or the policy database; those remain separate risks requiring operational security.

## Threat-to-control matrix

| ID | Threat / concrete trigger | Enforcement and observable behavior | Acceptance IDs | Remaining limitation |
|---|---|---|---|---|
| T01 | Prompt says to ignore instructions and disclose credentials | Detect selected patterns and deny provider-bound request | A02, A05 | Semantic, encoded, or novel attacks can evade detection |
| T02 | Retrieved document impersonates a system instruction | Preserve untrusted source, inspect chunk and assembled request; deny malicious fixture | A04 | Labels/delimiters alone do not guarantee model obedience |
| T03 | Input contains a credential or private-key marker | Secret detector; deny before upstream call; metadata-only logging | A03, A12 | Unsupported secret formats may be missed |
| T04 | Input/output contains selected PII | Redact configured spans before forwarding/release; no sensitive matched text in events | A06, A07, A18 | Selected formats only; benign format collisions possible |
| T05 | Model proposes a restricted or unknown tool | Default-deny registry/role/resource checks; execution counter stays unchanged | A08, A09 | Direct application-to-tool paths bypass gateway enforcement |
| T06 | Caller supplies `role=operator`, tenant header, or policy override | Reject unknown fields and derive identity from credentials | A01, A10 | Compromised credentials retain their actual privileges |
| T07 | Arguments add arbitrary URL, shell command, or unsupported properties | Trusted schema, destination registry, resource scope; deny invalid arguments | A09, A11 | Schema-valid text can still be semantically unsafe |
| T08 | Model returns malformed or schema-invalid structured output | Parse/inspect/validate; deny output without returning raw rejected content | A13 | Valid JSON can still contain incorrect information |
| T09 | Tool description/result carries hidden instructions | Inspect metadata/results; reject unknown protocols/content; pin registry versions | A14 | A compromised tool can lie within a valid schema |
| T10 | Malicious URL or remote schema reference attempts network access | Fixed destinations; no remote `$ref` resolution; zero unexpected network calls | A11 | Trusted operator could register a dangerous destination |
| T11 | Cross-tenant event queries or policy updates | Authenticated scoping and management authorization | A15 | Requires Phase 3 storage and query enforcement |
| T12 | Repeated requests / huge payload / oversized response | Pre-parse byte bounds, shared rate limits, deadlines, concurrency rejection | A16, A17 | Distributed floods require perimeter controls beyond this demo |
| T13 | Inspector, Redis, or audit storage is unavailable | Explicit fail-closed protected path; no uninspected fallback | A05, A19 | Reduced availability is an intentional tradeoff |
| T14 | Error/log output leaks prompts or provider keys | Sanitized fixed errors and audit schema; no upstream exception/body dump | A12 | Debugging tools and operators still need disciplined access |
| T15 | Spoofed inspection spans or Unicode normalization corrupt redaction | Validate original UTF-8 boundaries, overlap handling, and findings limit | A18 | Mapping must be tested against actual detector implementation |
| T16 | Timeouts trigger repeated tool side effects | No automatic side-effect retry; mark uncertain outcome | A20 | Initial tools are read-only; exactly-once is not promised |
| T17 | Policy changes mid-operation or registry drift | Immutable version per operation; tool execution rechecks current policy | A21 | Revocation does not undo already completed external effects |

## Security claims and evaluation

Rules provide explainable baseline detections. A local classifier may improve coverage but is probabilistic. Record missed attacks and false positives, including legitimate discussion of injection techniques. Never equate a passing fixture suite with protection against all attacks.

Synthetic adversarial fixtures are appropriate for local tests. Do not send real PHIL data, credentials, customer data, or large adversarial evaluation batches to NaviGator. Review account/service requirements before optional hosted tests.

## Review triggers

Update the threat model when adding streaming, multimodal content, a new MCP method/transport, side-effecting tools, remote destinations, raw-content retention, public hosting, or a new identity system.
