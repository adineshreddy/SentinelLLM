# Corpus provenance and split protocol

Source: [deepset/prompt-injections](https://huggingface.co/datasets/deepset/prompt-injections/tree/4f61ecb038e9c3fb77e21034b22511b523772cdd), immutable revision `4f61ecb038e9c3fb77e21034b22511b523772cdd`.

The corpus supplies 546 training and 116 test rows with binary labels (`0` benign, `1` injection). Original labels are used. The dataset card provides very little annotation or source documentation. It contains top-level Apache-2.0 and nested CC-BY-4.0 metadata; [source card](source-card.md), [attribution and retained licenses](../../services/inspection/models/NOTICE.md) preserve that ambiguity explicitly. No third-party text is treated as an instruction to the application.

Download is explicit and version-pinned, TLS verified, bounded to 4 MB per file, and checked against the committed [source manifest](source-manifest.json). Raw parquet files are cached under ignored `raw/` and excluded from Docker builds. Core tests/inference need only the committed model artifact, not this cache or training dependencies.

Data hygiene precedes allocation: reject out-of-runtime-bound/empty examples and conflicting normalized exact labels; deduplicate exact-normalized rows within each original split. Group NFKC/casefold/word-normalized duplicates and word-trigram Jaccard similarities >= 0.8 into connected components. Remove training rows whose component overlaps the official test split. The v1 corpus excluded zero rows with these checks; approximate semantic/templated overlap can remain undetected.

Split the original training pool by group, with a fixed seed (`20261001`), into 328 fit rows, 109 calibration rows and 109 threshold-development rows. Fit TF-IDF vocabulary, IDF and logistic coefficients only on fit rows. Fit sigmoid calibration only on calibration rows. Select the operating threshold only on threshold-development rows. Preserve the official 116 test rows for frozen evaluation. All four partitions have disjoint measured group IDs and both classes.

[Split manifest](splits-v1.json) records row IDs, group IDs, labels and text hashes without prompt bodies. Training fingerprints the entire split protocol; evaluation verifies these IDs and hashes against the frozen manifest before scoring. The model family and hyperparameters are fixed for v1. No parameter, threshold or training text was changed after viewing held-out results.

The 32-case [engineering challenge](../fixtures/phase5-challenge-v1.json) is a separate CC0 synthetic diagnostic set written for SentinelLLM. It is never used for fitting, calibration or threshold selection and is not a real-world accuracy benchmark.
