# Phase 0 — Design package

**Date:** September 30, 2026  
**Status:** Design deliverables complete; live provider access remains unverified  
**Scope:** Specification, contracts, threat model, policies, decisions, fixtures, and implementation backlog

## Decisions established

- Java/Spring Boot owns all final policy and authorization decisions.
- Python/FastAPI reports detection findings; it never executes tools or grants access.
- A mock provider is the default. UF NaviGator is the first real development provider, using a configurable model, initially `gpt-oss-120b`.
- Start with buffered text chat and explicit RAG inspection; introduce tool execution in Phase 2.
- Application identity, tenant, policy, schemas, and upstream destinations come from trusted server configuration.
- Requests that require inspection stop when inspection fails. Tool execution has a separate enforcement boundary.
- Phase 1 is explicitly a single-process development profile. Durable audit and shared rate limits arrive in Phase 3.
- Terraform is part of Phase 7 and manages resources inside a separately bootstrapped local kind cluster.

## Evidence gathered

- Read PHIL's `src/chat/llm.py`, `src/common/config.py`, and relevant configuration to identify the NaviGator integration. No changes were made to PHIL.
- PHIL uses `ChatOpenAI`, environment-loaded credentials, a configurable base URL, and `gpt-oss-120b` as its configured/default model.
- UF's official documentation specifies `https://api.ai.it.ufl.edu/v1/chat/completions`. SentinelLLM will use an explicit `/v1` API base rather than blindly copying PHIL's root URL.
- This development machine has 16 GiB RAM. Hosted generation avoids attempting a 120B local model. Smaller local detectors will be selected after measurement.
- Inspected source/configuration without reading `.env` credentials, invoking model endpoints, or importing PHIL's configuration loader.

## Deliverables

| Artifact | Purpose |
|---|---|
| Product specification | Scope, identities, capabilities, limits, and milestones |
| Architecture | Service ownership, request lifecycle, and trust boundaries |
| Threat model | Concrete threats, controls, limitations, and test mapping |
| API/schema contracts | Proposed interfaces with machine-checkable examples |
| Policy and registry examples | Deterministic enforcement configuration |
| Provider integration note | Source evidence, endpoint normalization, and credential handling |
| Architecture decisions | Record implementation choices and tradeoffs |
| Acceptance matrix/fixtures | Define observable security outcomes |
| Phase 1 backlog | Dependency-ordered implementation work |

## Validation and remaining checks

Run the contract checker described in the root README. Positive examples must validate, intentionally invalid examples must fail, referenced files must exist, and policy/registry/example identifiers must agree.

**Verification result:** 15 JSON Schemas validated; 13 positive examples accepted; 6 intentional negative examples rejected; 14 desired-behavior fixtures checked against policy/registry and acceptance IDs; OpenAPI 3.1 validation passed. Local Markdown links and code fences were also checked.

Design checks are not application tests. No gateway throughput, detector accuracy, live API access, or protocol compliance is claimed.

Before the first live integration test, confirm the key's allowed model list and free allowance/budget in the NaviGator portal. Do not use PHIL's production configuration or change its files. This is not a blocker for mock-backed Phase 1 work.

Implementation versions and the Phase 2 MCP transport/SDK must be pinned when their stages begin. The Phase 2 design target is a constrained Streamable HTTP tool adapter with initialization and negotiated protocol version, not an arbitrary JSON-RPC forwarding endpoint.

The [Phase 1 backlog](acceptance.md#phase-1-implementation-backlog) is now implemented under the user's subsequent authorization; see [Phase 1 results](../phase-1/verification.md). This report records the original Phase 0 design scope.
