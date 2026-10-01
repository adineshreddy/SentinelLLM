# SentinelLLM

AI security gateway for LLM applications, RAG pipelines, and agents.

**Current state:** Phases 0–6 implemented. Phase 7 adds GitHub Actions, a dedicated kind/Calico Kubernetes deployment, Terraform-managed workloads and tested network restrictions, plus deployment/recovery guides. Final local recreation and hosted CI validation are in progress. The gateway and authenticated console retain policy/tool/schema enforcement, durable PostgreSQL evidence, Redis quotas, opt-in local ML detection, Prometheus/Grafana and measured local performance. Rule-only inspection and mock generation remain the defaults.

**Stack:** Java/Spring Boot gateway and policy authority; Python/FastAPI detection; React/TypeScript console with a Node.js BFF; PostgreSQL; Redis; Docker; local Kubernetes; Terraform; GitHub Actions; Prometheus/Grafana.

## Start here

- [Overall project plan](PROJECT_PLAN.md)
- [Phase 7 deployment and CI guide](docs/phase-7/README.md)
- [Phase 7 recovery runbook](docs/phase-7/runbook.md)
- [Phase 7 verification evidence](docs/phase-7/verification.md)
- [Phase 6 monitoring and reliability guide](docs/phase-6/README.md)
- [Phase 6 measured performance](docs/phase-6/performance.md)
- [Phase 6 verification results](docs/phase-6/verification.md)
- [Phase 5 local classifier and run guide](docs/phase-5/README.md)
- [Phase 5 evaluation report](docs/phase-5/evaluation-v1.md)
- [Phase 5 verification results](docs/phase-5/verification.md)
- [Phase 4 console and demo guide](docs/phase-4/README.md)
- [Phase 4 verification results](docs/phase-4/verification.md)
- [Phase 3 run guide](docs/phase-3/README.md)
- [Phase 3 verification results](docs/phase-3/verification.md)
- [Phase 2 run guide](docs/phase-2/README.md)
- [Phase 2 verification results](docs/phase-2/verification.md)
- [Phase 1 run guide](docs/phase-1/README.md)
- [Phase 1 verification results](docs/phase-1/verification.md)
- [Phase 0 report and next steps](docs/phase-0/README.md)
- [Product specification](docs/phase-0/product-spec.md)
- [Architecture and trust boundaries](docs/phase-0/architecture.md)
- [Threat model](docs/phase-0/threat-model.md)
- [API contracts](contracts/README.md)
- [Policy evaluation specification](docs/phase-0/policy-spec.md)
- [NaviGator integration findings](docs/phase-0/navigator-integration.md)
- [Acceptance criteria and Phase 1 backlog](docs/phase-0/acceptance.md)
- [Architecture decisions](docs/decisions/0001-foundation.md)
- [Console trust boundary](docs/decisions/0003-console-boundary.md)
- [Local classifier decision](docs/decisions/0004-local-classifier.md)

## Run the working demo

```sh
python3 tools/setup_dev.py
docker compose up -d --build --wait
python3 tools/demo.py
python3 tools/demo_phase2.py
python3 tools/demo_phase3.py
python3 tools/console_login.py --account operator
```

The gateway binds to `127.0.0.1:8080`; PostgreSQL, Redis, inspection, and MCP adapter services stay inside the container network. Generated development credentials are private, Git-ignored, and excluded from image builds. Open the console at **http://127.0.0.1:3000** and paste the console password copied by the helper. Use `--account viewer` for read-only access. See the Phase 4 guide for the five-minute walkthrough, the Phase 3 guide for management, persistent state, and tests; the Phase 2 guide for tool/JSON behavior; and the Phase 1 guide for opt-in NaviGator setup.

## Development and demonstration budget

All required infrastructure can run locally. Mock model responses are the default for tests and demonstrations. UF NaviGator is the selected hosted development integration, with configurable model selection; its account-specific permissions, quotas, and budget must be checked before live requests. Local Ollama is an optional alternative, not a requirement to run a 120B model on a laptop.

Terraform Community Edition manages workloads in a dedicated local kind cluster. Paid cloud provisioning is outside the required scope.

## Contract verification

Use an isolated Python environment, install `contracts/requirements-validation.txt`, and run:

```sh
python tools/validate_contracts.py
```

This checks draft schemas, example payloads, negative cases, OpenAPI 3.1 validity, and local references. It does not execute application security controls or contact NaviGator. Implementation parity checks will be required when the services exist.

The deterministic fixtures in `evaluation/fixtures/phase0-cases.json` specify desired behavior. They are not measured detector results.
