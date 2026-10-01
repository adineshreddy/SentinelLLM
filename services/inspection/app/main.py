"""Internal rule and optional local-ML inspection; Java remains policy authority."""
from __future__ import annotations

import asyncio
import hmac
import json
import math
import os
import re
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.exceptions import NoSuchResource

from app.classifier import Classifier, TfidfClassifier

CATEGORIES = ("prompt_injection", "jailbreak", "secret", "pii")
VERSION = "rules-v1"
RULES = [
    ("PI.OVERRIDE", "prompt_injection", "high", r"\b(?:ignore|disregard|forget)\s+(?:all\s+)?(?:previous|prior|system)\s+(?:instructions|rules|prompts)\b"),
    ("PI.EXFILTRATION", "prompt_injection", "high", r"\b(?:reveal|disclose|dump|exfiltrate|print)\s+(?:your|the|all|internal|system|private)\s+(?:system\s+prompt|credentials|secrets|api\s+keys)\b"),
    ("JB.UNRESTRICTED", "jailbreak", "high", r"\b(?:(?:unrestricted|developer|DAN)\s+(?:mode|persona)|(?:bypass|disable)\s+(?:all\s+)?(?:safety|security)\s+(?:restrictions|filters|rules))\b"),
    ("SECRET.PRIVATE_KEY", "secret", "critical", r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    ("SECRET.OPENAI", "secret", "critical", r"\bsk-(?:proj-|ant-)?[A-Za-z0-9_-]{20,}\b"),
    ("SECRET.AWS", "secret", "critical", r"\bAKIA[A-Z0-9]{16}\b"),
    ("SECRET.GITHUB", "secret", "critical", r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b"),
    ("SECRET.ASSIGNMENT", "secret", "high", r"\b(?:api[_-]?key|password|secret|access[_-]?token)\s*[:=]\s*[\"']?[A-Za-z0-9_./+=-]{8,}"),
    ("PII.EMAIL", "pii", "medium", r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"),
    ("PII.SSN", "pii", "high", r"\b[0-9]{3}-[0-9]{2}-[0-9]{4}\b"),
]
COMPILED = [(rule, category, severity, re.compile(pattern, re.I)) for rule, category, severity, pattern in RULES]


def inspect_segments(operation_id: str, segments: list[dict], classifier: Classifier | None = None) -> dict:
    findings = []
    versions = dict.fromkeys(CATEGORIES, VERSION)
    if classifier:
        versions["prompt_injection"] = VERSION + "+" + classifier.version
    def report(status="complete", truncated=False):
        return {"operation_id": operation_id, "status": status, "truncated": truncated,
                "detector_versions": versions, "findings": findings if status == "complete" else []}
    for segment in segments:
        text = segment["text"]
        has_injection_rule = False
        for rule, category, severity, pattern in COMPILED:
            for match in pattern.finditer(text):
                if len(findings) == 100:
                    return report("failed", True)
                has_injection_rule |= category == "prompt_injection"
                findings.append({"rule_id": rule, "category": category, "severity": severity,
                                 "segment_id": segment["id"], "start_byte": len(text[:match.start()].encode("utf-8")),
                                 "end_byte": len(text[:match.end()].encode("utf-8")), "detector_version": versions[category]})
        if classifier and text and not has_injection_rule:
            score = classifier.score(text)
            if type(score) not in (float, int) or not math.isfinite(score) or not 0 <= score <= 1:
                raise ValueError("Invalid classifier score")
            if score >= classifier.threshold:
                if len(findings) == 100:
                    return report("failed", True)
                # A document classifier does not localize malicious tokens. Conservative whole-segment span.
                findings.append({"rule_id": "ML.PROMPT_INJECTION", "category": "prompt_injection", "severity": "high",
                                 "segment_id": segment["id"], "start_byte": 0, "end_byte": len(text.encode("utf-8")),
                                 "detector_version": versions["prompt_injection"], "score": round(float(score), 8)})
    return report()


def configured_classifier() -> Classifier | None:
    mode = os.getenv("SENTINEL_CLASSIFIER_MODE", "off")
    if mode == "off":
        return None
    if mode != "enforce":
        raise RuntimeError("Classifier mode must be off or enforce.")
    custom = os.getenv("SENTINEL_CLASSIFIER_PATH")
    try:
        if custom:
            return TfidfClassifier.load(Path(custom), os.environ["SENTINEL_CLASSIFIER_SHA256"])
        directory = Path(__file__).resolve().parent.parent / "models"
        manifest = json.loads((directory / "manifest.json").read_text())
        if manifest.get("file") != "tfidf-pi-v1.json":
            raise ValueError("Unsupported model filename")
        return TfidfClassifier.load(directory / "tfidf-pi-v1.json", manifest["sha256"])
    except Exception:
        raise RuntimeError("Configured classifier could not be verified. Inspection will not start.") from None


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate field")
        result[key] = value
    return result


def check_depth(value, depth=0):
    if depth > 32:
        raise ValueError("Depth exceeded")
    if isinstance(value, str):
        value.encode("utf-8", "strict")
    elif isinstance(value, dict):
        for key, child in value.items():
            key.encode("utf-8", "strict")
            check_depth(child, depth + 1)
    elif isinstance(value, list):
        for child in value:
            check_depth(child, depth + 1)


def error(status, code):
    return JSONResponse({"error": {"code": code, "message": "Inspection request could not be processed."}}, status_code=status)


def create_app(key: str | None = None, schemas: Path | None = None, classifier: Classifier | None = None) -> FastAPI:
    key = key if key is not None else os.getenv("SENTINEL_INSPECTION_KEY", "")
    if len(key) < 32:
        raise RuntimeError("Inspection service credential must be configured.")
    classifier = classifier if classifier is not None else configured_classifier()
    configured_directory = os.getenv("SENTINEL_SCHEMAS_DIR")
    directory = schemas or (Path(configured_directory) if configured_directory
                            else Path(__file__).resolve().parents[3] / "contracts/schemas")
    documents = [json.loads(p.read_text()) for p in directory.glob("*.json")]

    def reject_remote(uri):
        raise NoSuchResource(ref=uri)

    registry = Registry(retrieve=reject_remote).with_resources((d["$id"], Resource.from_contents(d)) for d in documents)
    contract = next(d for d in documents if d.get("title") == "inspection-request")
    validator = Draft202012Validator(contract, registry=registry, format_checker=FormatChecker())
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)

    @app.middleware("http")
    async def boundary(request: Request, call_next):
        if request.url.path != "/health/live":
            header = request.headers.get("authorization", "")
            if len(header) > 1024 or not hmac.compare_digest(header.encode(), ("Bearer " + key).encode()):
                return error(401, "UNAUTHENTICATED")
        if request.url.path == "/internal/v1/inspect" and request.method == "POST":
            if request.headers.get("content-type", "").split(";")[0].strip().lower() != "application/json":
                return error(400, "INVALID_REQUEST")
            async def read():
                body = bytearray()
                async for chunk in request.stream():
                    if len(body) + len(chunk) > 262144:
                        raise OverflowError()
                    body.extend(chunk)
                return body
            try:
                body = await asyncio.wait_for(read(), timeout=3)
                payload = json.loads(body.decode("utf-8", "strict"), object_pairs_hook=strict_object,
                                     parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
                check_depth(payload)
                if not validator.is_valid(payload):
                    return error(400, "INVALID_REQUEST")
                ids = [s["id"] for s in payload["segments"]]
                if len(set(ids)) != len(ids):
                    return error(400, "INVALID_REQUEST")
                request.state.payload = payload
            except OverflowError:
                return error(413, "PAYLOAD_TOO_LARGE")
            except asyncio.TimeoutError:
                return error(503, "DEPENDENCY_UNAVAILABLE")
            except Exception:
                return error(400, "INVALID_REQUEST")
        try:
            return await call_next(request)
        except Exception:
            return error(503, "DEPENDENCY_UNAVAILABLE")

    @app.get("/health/live")
    async def health():
        return {"status": "up"}

    @app.post("/internal/v1/inspect")
    def inspect(request: Request):
        payload = request.state.payload
        return inspect_segments(payload["operation_id"], payload["segments"], classifier)

    return app
