# ADR 0004 — An opt-in lexical ML baseline with offline inference

Status: accepted in Phase 5.

## Decision

Keep secret/PII/jailbreak/injection rules and add a pluggable, immutable local classifier interface in the Python inspector. The first implementation is a TF-IDF word-unigram/bigram logistic-regression model, trained with scikit-learn and exported as bounded, digest-verified JSON. Standard-library Python performs inference. No pretrained weights, NumPy/scikit-learn runtime, remote scoring endpoint, pickle loading, or startup download is required.

ML is off by default. `SENTINEL_CLASSIFIER_MODE=enforce` loads the approved artifact at startup. Invalid configuration/model integrity prevents startup; inference failures return a dependency error and Java refuses execution. The configured threshold belongs to the versioned model artifact.

A positive document score emits `ML.PROMPT_INJECTION`, mapped to the existing prompt_injection category and stage actions. A negative score cannot remove a rule finding. Positive ML findings cover the entire original UTF-8 segment because this classifier does not identify a malicious substring. Existing Java schema, span, rule/category, detector-version, policy, rate and audit checks still apply.

## Evidence and tradeoff

The 116-row frozen public test set gave 42/60 attack detections and 0/56 benign flags. A separate 32-case synthetic diagnostic set flagged 6/16 benign examples and missed 2/16 attacks. These results show a useful baseline and material domain shift. They support opt-in research/demo enforcement, with rule-only defaults retained, rather than automatic production rollout.

Fitting, sigmoid calibration and threshold selection use separate development partitions. Official test rows never determine hyperparameters or thresholds. The report includes confusion counts, precision/recall/FPR, probability metrics, small-sample intervals and known misses. Corpus/license provenance and numerical export parity are retained.

## Consequences

The artifact is small and local CPU cost is measurable. It provides concrete ML and evaluation experience while keeping core tests free and offline. It is lexical; semantic/context-aware protection remains future research. Dataset labels are binary injection labels, so ML does not replace secret/PII detection or measure all jailbreak families. Public-corpus provenance is sparse and its license metadata conflicts; both source notices/license texts are preserved with the model.

The existing NaviGator adapter remains available separately. Optional Ollama generation is deferred because it is not required to demonstrate local classification or reproduce this evaluation. No live provider calls are made in this phase.
