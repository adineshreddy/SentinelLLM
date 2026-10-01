# API contracts v1 — design baseline

**Status:** Phase 1 chat, advisory inspection, internal inspection, and liveness endpoints are implemented. Phase 2 discovery, independently authorized execution, model proposals, and registered structured output are implemented. Phase 3 adds tenant-scoped configuration/history/events/operation APIs and dependency readiness. JSON Schema dialect: Draft 2020-12. OpenAPI version: 3.1.0.

## Surfaces

| Endpoint | Boundary and semantics | Phase |
|---|---|---|
| `POST /v1/chat/completions` | Authenticated buffered text-chat subset; real enforcement before provider forwarding/release | 1 |
| `POST /api/v1/inspect` | Authenticated advisory inspection; caller must obey returned decision; never forwards or executes | 1 |
| `POST /internal/v1/inspect` | Internal Java→Python findings only; service credential required | 1 |
| `GET /api/v1/tools` | Authenticated role-filtered trusted tool discovery | 2 |
| `POST /api/v1/tools/execute` | Registered constrained tool execution; re-authorize every call | 2 |
| `GET /api/v1/management/config` | Viewer/operator reads active tenant revision | 3 |
| `PUT /api/v1/management/config` | Operator atomically activates policy/registry pair with expected revision | 3 |
| `GET /api/v1/management/config/history` | Immutable tenant revision history | 3 |
| `GET /api/v1/management/events` | Sanitized tenant-scoped event pages | 3 |
| `GET /api/v1/management/operations/{uuid}` | Durable tenant-scoped trace | 3 |
| `GET /health/ready` | PostgreSQL/Redis dependency check in persistent mode | 3 |
| `GET /health/live` | Minimal liveness; no secrets/configuration | 1 |

Management APIs are implemented in [Phase 3](../docs/phase-3/README.md). The constrained private MCP transport/session subset is documented in [Phase 2](../docs/phase-2/README.md). Do not treat the custom tool endpoint as a full MCP server. The current OpenAPI file is a validated design-level schema map; generated implementation parity checks are later acceptance requirements.

## Identity and statuses

Use `Authorization: Bearer <application credential>` on public protected APIs. Inspection and MCP use distinct internal credentials. Tenant/roles/policy are derived server-side; those fields are absent from public schemas. Health is unauthenticated and reveals only `status`.

Chat/tool execution: 200 approved; 400 unsupported or malformed input; 401 unauthenticated; 403 policy/permission denial; 404 unknown/cross-tenant operation; 409 stale management revision; 413 byte limit; 429 request/concurrency limit; 502 invalid/upstream failure; 503 inspection/rate/audit dependency failure or uncertain tool outcome; 504 upstream timeout. Validation errors include stable codes and correlation IDs, never offending values. A syntactically valid advisory inspection returns 200 even when its `decision.action` is `deny`.

Every gateway operation receives a generated UUID and `X-Sentinel-Operation-ID` response header. Error bodies use fixed messages and optional correlation ID; no provider error-body passthrough. JSON duplicates and non-finite numbers are rejected.

## Chat compatibility and extension

Initial fields: `model`, `messages`, `max_tokens`, `temperature`, `stream:false`, and optional `sentinel.context`. Message roles in Phase 1 are `system`, `user`, `assistant`, with text strings only. `sentinel.context` carries untrusted retrieved chunks, assembled by the gateway; it is never forwarded as an unknown upstream field.

`model` must match the authenticated application's allowed models. No arbitrary URL/key/schema/policy options. Phase 2 adds `sentinel.output_schema_id` and `sentinel.tool_ids`. Tool proposals are validated but never executed automatically. Complete tool history is deferred. Client-supplied tool definitions are rejected. Unsupported `stream:true`, image/audio content, and unknown fields fail explicitly.

The mock/NaviGator adapters translate to supported provider fields, not raw proxying of everything. Success responses expose only the supported assistant text or validated tool proposals, optional usage, and `sentinel` metadata. Raw provider reasoning/debug/extra fields are not relayed blindly.

## Inspection format

The public preview receives `segments` with ID, stage, path, and text. The internal request adds a generated `operation_id`. Paths use JSON Pointer notation for input location; they do not authorize any file/resource access.

Python returns `findings`, detector versions, completion status, and truncation status. All required detectors must complete; partial/truncated results deny in Java. Locations are original-text half-open UTF-8 byte offsets. Rule findings omit scores; a future ML score is optional and versioned. Findings never contain matched text.

Preview decisions may return replacement segments for `redact`. Denied previews omit replacement payloads. Preview approval is not reusable execution authorization. Audit events never include preview text or replacement segments.

## Files and examples

- `schemas/`: request/response, policy, registry, decision, error, and audit definitions.
- `examples/`: positive examples plus a manifest describing invalid samples.
- `openapi.json`: supported HTTP surfaces and local schema references.
- `../policies/examples/`: sample tenant policy and tool/output registry.
- `../evaluation/fixtures/`: deterministic desired-behavior cases.

The checker resolves references only from files under this project. `$schema` and `$id` identify the schema dialect/document; they are not permission to fetch arbitrary URLs. Registry argument/output schemas use a deliberately restricted vocabulary and reject remote references.

## Validation command

```sh
python -m venv /tmp/sentinellm-contract-check
/tmp/sentinellm-contract-check/bin/python -m pip install -r contracts/requirements-validation.txt
/tmp/sentinellm-contract-check/bin/python tools/validate_contracts.py
```

No provider credentials or live network calls are needed by the checker. Dependency installation downloads the validator package; that is separate from model traffic.
