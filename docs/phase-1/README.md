# Phase 1 — Working gateway baseline

[Phase 3](../phase-3/README.md) now supplies persistent PostgreSQL state, shared Redis admission, and management APIs. Local-file/process-only descriptions below record this earlier baseline.

This records the Phase 1 baseline. [Phase 2](../phase-2/README.md) now implements the tool, MCP, and structured-output features marked as deferred below.

## Implemented scope

- Java 21 target, Spring Boot 3.5.16, Maven 3.9.16 wrapper. Locally tested with Java 25; container runtime is Temurin 21.
- Internal Python 3.14/FastAPI inspection with pinned runtime and development dependency locks.
- SHA-256 mapped application credentials, separate inspection credential, trusted tenant/roles/model allowlist.
- Buffered text-chat subset at `POST /v1/chat/completions` and advisory content inspection at `POST /api/v1/inspect`.
- Explainable injection/jailbreak patterns, selected credential formats, email and SSN patterns. These are baseline detectors, not universal protection or validated classifier accuracy.
- Deny-first policy evaluation, original UTF-8 byte spans, overlap-safe redaction, RAG chunk inspection and inspection of the exact assembled payload.
- Output inspection before release. Unsupported provider tool calls, non-text content, and invalid responses fail closed; tool execution is Phase 2.
- Single-process fixed-window limits, two concurrent chat operations, eight concurrent inspection operations, bounded request/response bodies and upstream deadlines.
- Metadata-only JSONL decisions with write-before-forward/release behavior. Database durability and shared Redis limits are Phase 3.
- Mock provider and opt-in NaviGator adapter with fixed trusted destination, normalized `/v1`, backend-only key, no redirects, and no automatic retries.
- Non-root containers, loopback-only gateway binding, internal inspection service, health checks, and persistent audit volume.

No real NaviGator key was read or copied from PHIL. No hosted generation request is required by any default test or demo.

## Run the demo

From the repository root:

```sh
python3 tools/setup_dev.py
docker compose up -d --build --wait
python3 tools/demo.py
```

Setup creates `.env` with mode 0600 and independently generated application/inspection credentials. It preserves an existing file. The file is Git-ignored and excluded from Docker build context. It uses Compose dotenv syntax; do not source it as a shell script.

The demo prints synthetic responses for benign traffic, injection denial, credential-marker denial, PII redaction, poisoned RAG, and unauthenticated rejection. The gateway runs at `http://127.0.0.1:8080`; there is no web console yet. Health is available at `/health/live`. Preview responses can show sanitized text without forwarding anything.

Request budget is 30 accepted operations per application per 60-second fixed window. Repeated demo runs may return 429 until the next window. Restarting the single-process gateway resets its local counters; this is a development limitation, not a distributed rate-limit guarantee.

```sh
docker compose ps
docker compose logs --tail=30 gateway inspection
docker compose down
```

`down` retains the project's audit volume. Remove that volume only when intentionally discarding its development event history. Do not use `docker compose config` without `--quiet` in shared recordings: resolved environment values include private credentials.

## Run the checks

Install Python development dependencies once, then run the Java/Python suite:

```sh
python3 -m venv services/inspection/.venv
services/inspection/.venv/bin/python -m pip install -r services/inspection/requirements-dev.lock
tools/check.sh
```

The Maven wrapper downloads its pinned distribution on first use. Java 21 or newer compatible with the pinned Spring release is needed for local Java tests. No system Maven installation is required.

With the local mock-mode stack running, opt into Compose integration tests:

```sh
SENTINEL_COMPOSE_TESTS=1 services/inspection/.venv/bin/python -m pytest -q tests/integration/test_compose.py
```

These tests stop/restart the project's inspection service and recreate the gateway with synthetic output fixtures, then restore normal mock mode. They must not be run against a shared or live-provider environment. They verify real-service Unicode redaction, RAG denial, inspection outage/recovery, output secret denial, and output PII redaction.

Contract validation remains separate:

```sh
/tmp/sentinellm-contract-check/bin/python tools/validate_contracts.py
```

Create that environment following `contracts/README.md` if it does not exist.

## NaviGator development integration

The adapter is implemented and tested with recording/fake HTTP transport; live account access is unverified. The first configured model is `gpt-oss-120b`; any subsequently selected model must be in the trusted application allowlist and available to the key.

Before any live call, check the account's allowed models, expiration, rate limits, and budget/free allowance. Privately edit SentinelLLM's own `.env`:

- Set `SENTINEL_PROVIDER=navigator`.
- Set `SENTINEL_HOSTED_ENABLED=true`.
- Set `NAVIGATOR_API_KEY` privately. Never put it in the frontend, command examples, repository, or screenshots.
- Keep `NAVIGATOR_BASE_URL=https://api.ai.it.ufl.edu/v1`.
- To change model, update the credential entry's `models` allowlist, the request's `model`, and the demo convenience value `NAVIGATOR_LLM_MODEL`. The gateway uses the request model subject to its allowlist; it does not silently replace it.

Restart with `docker compose up -d --wait`. `tools/demo.py` intentionally refuses a non-mock `.env`; real smoke requests are separate and should use short synthetic content only. Default generation is 256 tokens, maximum 1,024. Unknown provider/model or missing credentials do not silently fall back to another provider.

The selected provider origin is fixed to UF NaviGator. Supporting another host requires an explicit trusted adapter/configuration change, not a client-supplied URL.

## Evidence and acceptance mapping

The Java suite covers A01–A07, A10–A12 within Phase 1 scope, A16–A19 in development mode, immutable startup policy snapshots for A21, and mock/fake transport behavior for A22. Python tests cover starter content fixtures, byte offsets, overflow, malformed inputs, service authentication, and container schema lookup.

A11's remote output/tool-schema tests and tool routing are deferred to Phase 2. A21's atomic policy updates are deferred to Phase 3. Authentication/protocol errors rejected before policy evaluation are sanitized HTTP errors; they are not yet a complete persisted authentication-event stream. Metrics/security dashboards are Phase 6.

See [verification results](verification.md) for counts and commands. Passing fixtures do not establish detection effectiveness on unseen attacks.

## Remaining limitations

- One tenant policy per running gateway process; identities must belong to that configured tenant. Multi-tenant policy storage/management is Phase 3.
- Selected English pattern rules and selected PII formats only; no semantic/local ML classifier yet.
- No streaming, tool execution, structured JSON output enforcement, MCP server, UI, PostgreSQL, Redis, Terraform deployment, or full OpenAI compatibility yet.
- Startup-loaded policy is immutable until restart. No policy update API yet.
- Local logs are not tamper-proof or transactional. A failed disk write stops protected work, but partial disk writes can leave an incomplete final event line.
- Liveness checks do not claim model readiness, quota availability, or security-service readiness for every request; protected requests check dependencies and fail closed.
- Container networking is local development networking, not production mTLS or Kubernetes network-policy enforcement.

Next milestone: Phase 2 tool registry, authorization, argument/output validation, and constrained execution.
