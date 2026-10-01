# Corpus and model artifact attribution

The `tfidf-pi-v1.json` model was trained from **deepset/prompt-injections**, published by deepset on Hugging Face, revision `4f61ecb038e9c3fb77e21034b22511b523772cdd`.

Source: https://huggingface.co/datasets/deepset/prompt-injections/tree/4f61ecb038e9c3fb77e21034b22511b523772cdd

The publisher's pinned dataset card has conflicting license metadata: top-level `license: apache-2.0` and nested `dataset_info.license: cc-by-4.0`. Both notices and license texts are retained here. The source card is preserved verbatim in `evaluation/data/source-card.md`; checksums of the original card and parquet files are recorded in `evaluation/data/source-manifest.json`. This ambiguity is recorded rather than silently resolved to one license. Redistribution of the original corpus is outside the repository's default workflow; users download the small pinned public corpus explicitly for offline reproduction.

Changes by SentinelLLM: runtime-bound filtering; normalized exact/near-duplicate family grouping; separated fitting, probability calibration, threshold selection and evaluation; TF-IDF/logistic-regression training; sigmoid calibration; JSON coefficient export and a standard-library inference implementation. The artifact contains learned numerical parameters and vocabulary, with no pickle or executable code. The model is an experimental lexical baseline. Dataset labels were retained from the publisher.

Attribution to deepset and the original source URL must accompany the corpus-derived model artifact. The included Apache 2.0 and CC BY 4.0 texts apply to the source/derived-data notices described here. The engineering-authored challenge fixture is separately marked CC0-1.0.
