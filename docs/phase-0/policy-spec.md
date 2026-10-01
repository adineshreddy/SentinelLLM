# Policy specification v1

Machine-checkable examples are in `policies/examples/`; their schemas are in `contracts/schemas/`. These are development policies, not calibrated production settings.

## Trusted binding and lifecycle

An authenticated application maps to a server-owned tenant and active policy ID/version. Request bodies cannot choose roles, tenants, policy versions, detector disabling, upstream URLs, or arbitrary output schemas. Policies and tool registries are immutable snapshots. Operator updates create new versions, validate before activation, and reject optimistic-version conflicts.

Phase 1 loads a validated file at startup. Phase 3 persists validated policy versions transactionally. Every operation uses one snapshot throughout its chat lifecycle; tool execution is a new operation and uses the snapshot current at execution time.

## Deterministic evaluation order

1. Validate authentication, request shape, server-configured model/schema selection, and limits.
2. For tools, resolve registry entry, caller permissions, resource scope, and argument schema before execution.
3. Request required content detectors; all configured detectors must complete successfully.
4. Evaluate findings according to category and stage. `deny` takes precedence over `redact`; `redact` over `allow`.
5. Redact only supported textual surfaces; block tool arguments requiring redaction. Merge overlapping spans, replace with a fixed marker, and validate the new structured shape if applicable.
6. Reinspect the exact assembled outbound content. A redaction result does not skip final inspection.
7. Record the decision before execution; inspect/validate results before release.

No role bypasses content checks. Permissions are explicit per role; multiple roles union explicit tool permissions within the same tenant, subject to global content policy and resource constraints. Management roles do not imply tool rights.

## Findings and scores

Categories: `prompt_injection`, `jailbreak`, `secret`, `pii`. Stages: `prompt`, `rag`, `response`, `tool_arguments`, `tool_result`.

Rule detections have stable IDs and severity. Do not invent numeric confidence for regex hits. ML findings may include a score only with model/version and documented interpretation; policy thresholds and calibration enter Phase 5 through a versioned contract change.

All locations refer to original segment bytes. Findings contain no matched text or detector-generated free-form explanation. UI descriptions come from a trusted rule catalog keyed by rule ID.

## Default examples

`support-default-v1.json` denies injection/jailbreak/secret findings on every surface. PII redacts prompt/RAG/response/tool results; it denies tool arguments. The initial output schema `support-answer-v1` permits a bounded answer and source IDs. Unknown tools and unexpected properties are denied.

`support_agent` can call `kb.search` and `ticket.get` only. `ticket.get` permits `DEMO-` ticket IDs; no remote account access. `viewer`/`operator` cannot execute tools unless granted a separate explicit role. A denied tool fixture may mention `ticket.delete`, but that tool is not an executable registered capability.

## Validation boundaries

JSON Schema checks structure, enums, and bounds. Runtime semantic checks must also require increasing offsets, valid UTF-8 boundaries, known categories/rules, compatible registry entries, unique IDs, correct tenancy, and no prohibited schema references. JSON duplicate keys and non-finite numbers are rejected before validation.

The v1 schema intentionally supports a constrained policy format. Arbitrary regex expressions, user-defined code, custom remote models, inline schemas, and monitor-only authorization bypasses are excluded.
