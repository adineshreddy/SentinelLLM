# Product specification

## Product and users

SentinelLLM enforces security checks on application-to-model and agent-to-tool operations. The first demonstration is a support assistant using synthetic knowledge-base content and harmless local tools. It is not a medical application and does not reuse PHIL's datasets.

Users are application developers integrating the gateway, operators configuring policy, and viewers inspecting sanitized demo events. A model is never an identity or authorization authority.

## Personas and permission boundaries

| Role | Allowed operations |
|---|---|
| `support_agent` | Chat, content inspection, and explicitly registered read-only support tools |
| `viewer` | Read sanitized console events for its tenant; no tool execution or policy updates |
| `operator` | Manage its tenant's policies and registry; no automatic tool-execution permission |

Permissions are explicit rather than hierarchical. An operator does not automatically inherit agent privileges. Development credentials map to server-owned tenant/application/roles. Test credentials are generated locally and never documented as fixed usable keys.

## MVP progression

Phase 1: authenticated buffered chat, rule detectors, request/response decisions, integrated context inspection, advisory inspection, bounded requests, sanitized JSONL decision events, and mock-backed tests. Real NaviGator integration is opt-in.

Phase 2: trusted function registry, tool permissions, argument/output schema validation, tool result inspection, constrained execution, and MCP adapter.

Phase 3 onward: transactional configuration, durable audit, Redis limits, tenant-aware management, console, ML evaluation, metrics, Kubernetes, and Terraform.

## Actions

- `allow`: requested content satisfies the applicable policy; this is not a guarantee of semantic safety.
- `redact`: eligible sensitive spans are replaced before forwarding/releasing content. Schema-sensitive tool arguments are blocked instead of rewritten initially.
- `deny`: do not forward protected input, execute a tool, or release prohibited output.

Findings are distinct from actions. A rule identifies a concern; Java applies the selected policy. No finding is not equivalent to proof of safety.

## Initial limits

These are proposed defaults, not performance commitments. Enforce byte limits before parsing; model/token limits are independent.

| Limit | Default |
|---|---|
| HTTP request body | 256 KiB |
| Buffered provider/tool response | 512 KiB |
| Messages per chat request | 32 |
| Text length per message/segment | 16,384 Unicode code points, also bounded by body bytes |
| RAG chunks per operation | 8 |
| Findings per inspection | 100; overflow returns an explicit failure and denies |
| Inspection deadline | 3 seconds |
| Upstream generation deadline | 60 seconds total; never extend through automatic retries |
| Active hosted generation calls | 2 per process in development; excess rejected with 429 |
| Generation output | 256 tokens default, 1,024 maximum |
| Development requests | 30 accepted operations per identity per 60-second fixed window |

Aggregate tenant-wide limits for multiple application credentials arrive in Phase 3. Fixed-window limits can allow bursts across window boundaries; do not describe them as rolling-window guarantees. Content depth and schema complexity also need bounds in implementation.

## Non-goals for the first version

Streaming, image/audio inspection, arbitrary upstream routing, remote shell execution, unrestricted filesystem/database tools, broad MCP compliance, universal PII recognition, multi-region service, hosted public production operation, and automatic side-effecting retries.

## Demo requirements

Support the five scenarios in the project plan: benign request, PII redaction, poisoned context, unauthorized tool, and a versioned policy update. Display real backend decisions, original/sanitized content only for synthetic fixtures, and no provider credentials. Label mock and live modes.

## Evidence requirements

Publish enforcement integration tests, false positives/negatives, held-out detector results, measured gateway and detector latency, resource usage, deployment steps, and failure exercises. Use measured results only in portfolio claims.
