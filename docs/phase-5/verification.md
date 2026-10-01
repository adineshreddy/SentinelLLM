# Phase 5 verification

Verified locally on October 1, 2026. No hosted LLM calls, pretrained weight downloads, cloud infrastructure or GPU use. The final Compose stack is healthy in default rule-only/mock mode.

## Automated checks

| Check | Passed | Coverage |
|---|---:|---|
| Java gateway unit/HTTP tests | 60 | Existing enforcement plus scored ML input/output denial and invalid-score fail-closed handling |
| Python inspector tests | 37 | Rules, authentication, input bounds, artifact integrity/shape/finite parameters, duplicate fields, mode/startup behavior, scores and UTF-8 spans |
| MCP adapter tests | 13 | Existing private protocol and execution boundary |
| Credential setup tests | 3 | Private/idempotent credentials and preserved existing configuration |
| Console BFF tests | 17 | Server-held identities, roles, sessions, CSRF/Origin/Host, admission and guarded workflow |
| ML evaluation protocol tests | 7 | Normalization/grouping, deterministic disjoint partitions, leakage rejection, threshold budget/ties, uncertainty metrics and independent sklearn numerical parity |
| Backend Compose integration | 20 | Existing 16 regression checks plus four real ML prompt/RAG/tool/output enforcement checks |
| Browser console checks | 6 | Five rule-only console workflows plus one explicitly enabled ML-scoring walkthrough |
| **Total** | **163** | All completed checks passed |

The full 20-test Compose run passed in 136.81 seconds. After adding the artifact hash to runtime detector identity, the four ML tests were rerun and passed in 36.02 seconds. Subsequent strict JSON/score type checks passed native tests and the final rebuilt service loaded successfully for the enabled-ML browser/CLI demo.

Default console checks passed in 5.6 seconds, including the rule-only authority-spoof comparison. The separately enabled ML browser test passed in 1.1 seconds and saved [the scored playground screenshot](ml-playground.png). It verifies an actual gateway decision, ML rule ID, score and digest-bound detector version. The optional ML browser test is skipped in the default browser suite; run it explicitly against an enabled stack.

The separate 16-test native PostgreSQL/Redis suite from Phase 3 was not rerun and is excluded from this total. PostgreSQL metadata, operation state, policy versions, tenant isolation, Redis admission and outage behavior were exercised through the Compose suite.

## ML evidence

- Downloaded the immutable public corpus revision with TLS verification and retained SHA-256 checksums, source card and both reported license notices. Training ran offline after download.
- Split vocabulary/model fitting (328 rows), sigmoid calibration (109), threshold selection (109), and held-out evaluation (116). Measured normalized/near-duplicate groups are disjoint. Zero exclusions occurred in v1.
- Threshold 0.78 was selected using development rows only under an observed FPR <= 5% target. The model and threshold were frozen before held-out scoring; no tuning followed those results.
- Held-out ML: TP42, FN18, FP0, TN56; precision 100% observed, recall 70%, F1 0.824. FPR's Wilson 95% upper bound is 6.42%, so zero observed flags is not a population guarantee.
- Engineering challenge: TP14, FN2, FP6, TN10. Those false positives justify rule-only defaults and the opt-in enforcement designation.
- Calibrated held-out Brier score 0.0831, versus 0.1069 uncalibrated. Report includes ECE, full confusion counts, small-sample intervals, all evaluation identifiers/scores, known misses, hardware and local-function latency.
- Standard-library inference matched sklearn development scores within 1e-10. Fixed training reproduced the 118,087-byte model byte-for-byte in the same environment. Evaluation verifies the full model/split fingerprint chain before scoring.

[Readable evaluation](evaluation-v1.md) · [Machine-readable report](../../evaluation/reports/phase5-v1.json) · [Model manifest](../../services/inspection/models/manifest.json)

## Runtime and demo evidence

- The same synthetic authority-spoof preview is allowed in rule-only mode and denied by the current policy with ML enabled (score 0.95721944).
- Findings span the complete original UTF-8 segment, include the score and digest-bound detector version, and preserve rule detections. Java retains final authority over stage actions and execution.
- Prompt and RAG denials record no model dispatch. ML-positive tool arguments do not increment MCP execution counters. ML-positive model responses are blocked after provider return and before HTTP release.
- Invalid model/configuration prevents enforcement startup; invalid inference results produce a dependency error. No silent fallback to rules occurs when ML is configured.
- Existing secrets/PII, RBAC, MCP validation, structured output, persistence, audit privacy and outage recovery checks remain passing.
- Browser and CLI demonstrations restored `off` mode in cleanup. Existing policy settings are restored as a new revision; history is retained.
- Configured private credentials are absent from the client bundle. `.env` remains mode 0600. Contract validation passes unchanged: 21 schemas, 19 valid/7 invalid examples, 14 desired-behavior fixtures and OpenAPI consistency.

## Reproduce

```sh
tools/check.sh
evaluation/.venv/bin/python -m pytest evaluation/tests -q
evaluation/.venv/bin/python evaluation/prepare_corpus.py
evaluation/.venv/bin/python evaluation/evaluate_classifier.py
SENTINEL_COMPOSE_TESTS=1 services/inspection/.venv/bin/python -m pytest tests/integration -q
npm --prefix services/console run test:e2e
```

Explicit ML browser demonstration, after completing other shared-stack checks:

```sh
SENTINEL_CLASSIFIER_MODE=enforce docker compose up -d --wait inspection gateway
SENTINEL_ML_BROWSER_TEST=1 npm --prefix services/console run test:e2e -- e2e/classifier.spec.ts
python3 tools/demo_phase5.py
SENTINEL_CLASSIFIER_MODE=off docker compose up -d --wait inspection gateway
```

Keep Docker rebuilds, outage tests, mode changes and browser demonstrations sequential. Shared support quotas apply across runs. See the [run guide](README.md) to install the isolated evaluation dependencies and explicitly download the corpus for reproduction.

## Limits

These checks validate implementation and a measured lexical baseline. They do not establish production attack coverage. The corpus is small, its documentation sparse, and its license metadata inconsistent; notices preserve the source claims. Diagnostic domain shift is substantial. Optional Ollama generation, live NaviGator calls, end-to-end load/latency measurements, Prometheus/Grafana and deployment automation remain outside this phase.
