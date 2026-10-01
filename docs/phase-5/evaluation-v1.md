# Phase 5 v1 evaluation report

This is a frozen, local lexical baseline. ML is **off by default** because the challenge set exposes material false positives. No model generation or hosted API call contributed to training/evaluation.

## Corpus and split protocol

The pinned deepset/prompt-injections corpus contains 546 original training rows (343 benign, 203 injection) and 116 official test rows (56 benign, 60 injection). [Corpus provenance and licenses](../../evaluation/data/README.md), [source manifest](../../evaluation/data/source-manifest.json), [split manifest](../../evaluation/data/splits-v1.json), and [model manifest](../../services/inspection/models/manifest.json) record the inputs and artifact.

Group allocation gives 328 fit rows (210 benign/118 attack), 109 sigmoid-calibration rows (62/47), 109 threshold-development rows (71/38), and 116 frozen official test rows (56/60). Measured group IDs are disjoint across all four partitions. Exact normalized duplicates, conflicting labels, near-duplicate overlap and runtime bounds are checked; zero rows were excluded in v1. This does not prove semantic family independence in the sparse public corpus.

Fit TF-IDF unigrams/bigrams and balanced logistic regression (C=4) only on the fitting partition. Fit sigmoid calibration (C=100) on calibration rows. The threshold is **0.78**, selected from a fixed 0.01..0.99 grid to maximize development recall with observed development FPR <= 5%, then prefer lower FPR and higher threshold. Development results: TP26, FN12, FP3, TN68; recall 68.4%, FPR 4.23%. No choice was changed after viewing the held-out or challenge results.

## Detection results

These metrics concern binary prompt injection/jailbreak signals. Secret/PII rules are outside the classifier's label space. Rule predictions are positive when an injection or jailbreak regex fires; combined predictions are the union of rules and ML.

| Dataset | Detector | Attacks detected | Benign flagged | Precision | Recall | FPR | F1 |
|---|---|---:|---:|---:|---:|---:|---:|
| Public held-out corpus | rules only | 0/60 | 0/56 | 0.0% | 0.0% | 0.0% | 0.000 |
| Public held-out corpus | ml only | 42/60 | 0/56 | 100.0% | 70.0% | 0.0% | 0.824 |
| Public held-out corpus | rules plus ml | 42/60 | 0/56 | 100.0% | 70.0% | 0.0% | 0.824 |
| Engineering challenge | rules only | 2/16 | 1/16 | 66.7% | 12.5% | 6.2% | 0.211 |
| Engineering challenge | ml only | 14/16 | 6/16 | 70.0% | 87.5% | 37.5% | 0.778 |
| Engineering challenge | rules plus ml | 14/16 | 6/16 | 70.0% | 87.5% | 37.5% | 0.778 |

Zero rule detections on the public test split expose the narrow coverage of the existing explicit English patterns on that corpus. It is not a universal estimate of regex effectiveness. Zero observed ML false positives on 56 benign rows is also not a guarantee: the Wilson 95% upper FPR bound is **6.42%**. Held-out recall's Wilson interval is **57.5–80.1%**.

The separate 32-case engineering-authored CC0 challenge is diagnostic and never used for fitting/calibration/selection. It exposes six benign flags: code-writing, a quoted attack for security training, incident discussion, MCP explanation, a synthetic email request, and cooking-instruction translation. Two missed attacks ask for instruction-boundary violations in Spanish and polite language. These results show domain shift and prevent a production-ready claim.

## Probability calibration and model size

Held-out Brier score improves from 0.1069 without sigmoid calibration to **0.0831** with it; 10-bin ECE improves from 0.1320 to **0.0724**. On the challenge, calibrated Brier score is 0.2605 and ECE is 0.2706. Calibration is a corpus-relative estimate; a finding's score should not be interpreted as a universal probability that a real request is malicious.

The artifact contains **1,602 features** and is **118,087 bytes**, excluding manifests/notices. JSON inference was checked against scikit-learn scores on all development rows with a maximum allowed numerical error of 1e-10. Fixed training was reproduced byte-for-byte in the same environment. Cross-platform numerical libraries may differ; always validate and version a new artifact.

## Local inference latency

Measured on Darwin arm64, Apple M5, 10 logical CPUs, 16 GiB RAM, Python 3.14.2. There are 20 warm-up texts and five sequential repetitions of the 116 held-out samples, with `perf_counter` timing.

| Path | p50 | p95 | p99 |
|---|---:|---:|---:|
| Classifier | 0.0065 ms | 0.0253 ms | 0.0382 ms |
| Rule + optional ML inspection | 0.0141 ms | 0.0628 ms | 0.0980 ms |

Artifact load took 2.18 ms. A bounded ten-segment workload of 16,384 ASCII characters per segment (163,840 UTF-8 bytes total) took 14.20 ms. These are local function measurements, excluding HTTP, authentication, Java, Redis, PostgreSQL, provider time and training. They are not end-to-end throughput benchmarks; those belong to Phase 6.

## Known misses and limitations

All 18 held-out false-negative IDs are retained in the [machine-readable report](../../evaluation/reports/phase5-v1.json); no raw corpus text is copied into audit or report samples. The report includes every label, score, rule ID and text length, plus confusion matrices and uncertainty intervals.

This model uses word patterns and lacks instruction-source semantics. It can overreact to quoted security discussion and under-detect multilingual, obfuscated, encoded or diluted instructions. The small corpus's documentation is sparse, provenance and licensing metadata are imperfect, and near-duplicate checks cannot eliminate semantic overlap. Challenge fixtures are not an independent field benchmark. The v1 result is a measured starting point for domain-specific data collection, improved evaluation and stronger models.

Next model changes must use a new version and fresh held-out data or cross-validation protocol. Do not tune against these published held-out/challenge results and report them again as unseen evaluation.

[Machine-readable complete report](../../evaluation/reports/phase5-v1.json) · [Run guide](README.md)
