"""Bounded, offline TF-IDF inference from a reviewed JSON artifact; no pickle or ML runtime."""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import re
from types import MappingProxyType
from typing import Mapping, Protocol

TOKEN = re.compile(r"(?u)\b\w\w+\b")
VERSION = "tfidf-pi-v1"


def tokens(text: str) -> list[str]:
    return [token for token in TOKEN.findall(text.lower()) if len(token) <= 64]


def sigmoid(value: float) -> float:
    if value >= 0:
        return 1.0 / (1.0 + math.exp(-value))
    exponential = math.exp(value)
    return exponential / (1.0 + exponential)


class Classifier(Protocol):
    version: str
    threshold: float

    def score(self, text: str) -> float: ...


@dataclass(frozen=True)
class TfidfClassifier:
    vocabulary: Mapping[str, tuple[float, float]]
    intercept: float
    calibration_slope: float
    calibration_intercept: float
    threshold: float
    version: str = VERSION

    def score(self, text: str) -> float:
        if len(text) > 16384:
            raise ValueError("Classifier input exceeds segment bound")
        words = tokens(text)
        counts = Counter(words)
        counts.update(a + " " + b for a, b in zip(words, words[1:]))
        weighted = []
        for term, count in counts.items():
            entry = self.vocabulary.get(term)
            if entry:
                idf, coefficient = entry
                weighted.append(((1.0 + math.log(count)) * idf, coefficient))
        norm = math.sqrt(math.fsum(value * value for value, _ in weighted))
        logit = self.intercept
        if norm:
            logit += math.fsum(value * coefficient for value, coefficient in weighted) / norm
        return sigmoid(self.calibration_slope * logit + self.calibration_intercept)

    @classmethod
    def load(cls, path: Path, sha256: str) -> TfidfClassifier:
        if not re.fullmatch(r"[a-f0-9]{64}", sha256):
            raise ValueError("Invalid classifier digest")
        with path.open("rb") as handle:
            content = handle.read(1_000_001)
        if len(content) > 1_000_000 or hashlib.sha256(content).hexdigest() != sha256:
            raise ValueError("Classifier integrity check failed")
        def unique(pairs):
            obj = {}
            for key, value in pairs:
                if key in obj:
                    raise ValueError("Duplicate classifier field")
                obj[key] = value
            return obj
        data = json.loads(content.decode("utf-8", "strict"), object_pairs_hook=unique,
                          parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Non-finite model value")))
        expected = {"schema_version", "algorithm", "category", "version", "features", "intercept", "calibration", "threshold", "training_fingerprint"}
        if not isinstance(data, dict) or set(data) != expected or type(data["schema_version"]) is not int or data["schema_version"] != 1 or data["algorithm"] != "tfidf-logistic" or data["category"] != "prompt_injection" or data["version"] != VERSION or not isinstance(data["training_fingerprint"], str) or not re.fullmatch(r"[a-f0-9]{64}", data["training_fingerprint"]):
            raise ValueError("Unsupported classifier artifact")
        def finite(value, low, high):
            if type(value) not in (int, float) or not math.isfinite(value) or not low <= value <= high:
                raise ValueError("Invalid classifier parameter")
            return float(value)
        if not isinstance(data["features"], list) or not 1 <= len(data["features"]) <= 5000:
            raise ValueError("Invalid classifier vocabulary")
        vocabulary = {}
        for feature in data["features"]:
            if not isinstance(feature, dict) or set(feature) != {"term", "idf", "weight"}:
                raise ValueError("Invalid classifier feature")
            term = feature["term"]
            if not isinstance(term, str) or len(term) > 129 or term in vocabulary or not 1 <= len(term.split(" ")) <= 2 or " ".join(tokens(term)) != term:
                raise ValueError("Invalid classifier term")
            vocabulary[term] = (finite(feature["idf"], 1, 100), finite(feature["weight"], -1000, 1000))
        calibration = data["calibration"]
        if not isinstance(calibration, dict) or set(calibration) != {"slope", "intercept"}:
            raise ValueError("Invalid classifier calibration")
        return cls(MappingProxyType(vocabulary), finite(data["intercept"], -1000, 1000),
                   finite(calibration["slope"], 0.000001, 1000), finite(calibration["intercept"], -1000, 1000),
                   finite(data["threshold"], 0.01, 0.99), VERSION + "." + sha256)
