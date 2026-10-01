"""Offline Phase 0 schema/example consistency checks; no gateway execution."""

from __future__ import annotations

import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate as validate_openapi
from referencing import Registry, Resource
from referencing.exceptions import NoSuchResource

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / "contracts" / "schemas"


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def load(path):
    return json.loads(
        path.read_text(),
        object_pairs_hook=unique_object,
        parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)),
    )


def require(condition, message):
    if not condition:
        raise ValueError(message)


def objects(value):
    if isinstance(value, dict):
        yield value
        for nested in value.values():
            yield from objects(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from objects(nested)


def reject_remote(uri):
    raise NoSuchResource(ref=uri)


def validate_registry_schema(value):
    """Check the deliberately restricted example registry schema vocabulary."""
    allowed = {
        "$schema", "type", "properties", "required", "additionalProperties",
        "items", "minLength", "maxLength", "minimum", "maximum", "minItems",
        "maxItems", "pattern", "enum",
    }

    def visit(node, depth=0):
        require(depth <= 8, "Registry schema exceeds depth limit")
        require(isinstance(node, dict), "Registry schemas must be objects")
        require(set(node) <= allowed, "Unsupported registry schema keyword")
        if node.get("type") == "object":
            require(node.get("additionalProperties") is False, "Object must be closed")
        for child in node.get("properties", {}).values():
            visit(child, depth + 1)
        if "items" in node:
            visit(node["items"], depth + 1)

    Draft202012Validator.check_schema(value)
    visit(value)


def main():
    # Parse every JSON artifact with strict duplicate/non-finite checks.
    ignored={".venv","target","node_modules",".git","runtime"}
    json_files = [path for path in ROOT.rglob("*.json") if not ignored.intersection(path.relative_to(ROOT).parts)]
    for path in json_files:
        load(path)

    schemas = [load(path) for path in sorted(SCHEMAS.glob("*.json"))]
    registry = Registry(retrieve=reject_remote).with_resources(
        (schema["$id"], Resource.from_contents(schema)) for schema in schemas
    )
    for schema in schemas:
        Draft202012Validator.check_schema(schema)
        for node in objects(schema):
            if "$ref" in node:
                target = SCHEMAS / node["$ref"].split("#")[0]
                require(target.is_file(), f"Missing local schema reference: {target.name}")

    manifest = load(ROOT / "contracts/examples/manifest.json")
    for case in manifest:
        schema = load(ROOT / case["schema"])
        validator = Draft202012Validator(
            schema, registry=registry, format_checker=FormatChecker()
        )
        valid = validator.is_valid(load(ROOT / case["file"]))
        require(valid == case["valid"], f"Unexpected validation result: {case['file']}")

    policy = load(ROOT / "policies/examples/support-default-v1.json")
    tools = load(ROOT / "policies/examples/tool-registry-v1.json")
    ids = [tool["tool_id"] for tool in tools["tools"]]
    require(len(ids) == len(set(ids)), "Duplicate registered tool ID")
    for permitted in policy["roles"].values():
        require(set(permitted) <= set(ids), "Policy permits unregistered tool")
    for tool in tools["tools"]:
        validate_registry_schema(tool["input_schema"])
        validate_registry_schema(tool["output_schema"])
    output_ids = [item["id"] for item in tools["output_schemas"]]
    require(len(output_ids) == len(set(output_ids)), "Duplicate output schema ID")
    for item in tools["output_schemas"]:
        validate_registry_schema(item["schema"])

    fixtures = load(ROOT / "evaluation/fixtures/phase0-cases.json")["cases"]
    acceptance = (ROOT / "docs/phase-0/acceptance.md").read_text()
    require(len({case["id"] for case in fixtures}) == len(fixtures), "Duplicate fixture ID")
    for case in fixtures:
        require(case["stage"] in policy["actions"]["pii"], "Unknown fixture stage")
        require(case["role"] in policy["roles"], "Unknown fixture role")
        for identifier in case["acceptance"]:
            require(f"| {identifier} |" in acceptance, "Unknown acceptance ID")
        if "expected_category" in case:
            action = policy["actions"][case["expected_category"]][case["stage"]]
            require(action == case["expected_action"], "Fixture disagrees with policy")
        if "tool_id" in case:
            registered = next((tool for tool in tools["tools"] if tool["tool_id"] == case["tool_id"]), None)
            authorized = case["tool_id"] in policy["roles"][case["role"]]
            valid_args = registered is not None and Draft202012Validator(registered["input_schema"]).is_valid(case["arguments"])
            if not authorized or not valid_args:
                require(case["expected_action"] == "deny" and case["tool_calls"] == 0, "Invalid tool fixture must deny without execution")

    # Validate declared original-text spans in examples, including UTF-8 boundaries.
    request = load(ROOT / "contracts/examples/inspection-request.json")
    response = load(ROOT / "contracts/examples/inspection-response.json")
    require(request["operation_id"] == response["operation_id"], "Operation mismatch")
    segments = {segment["id"]: segment for segment in request["segments"]}
    for finding in response["findings"]:
        raw = segments[finding["segment_id"]]["text"].encode("utf-8")
        start, end = finding["start_byte"], finding["end_byte"]
        require(0 <= start < end <= len(raw), "Invalid span ordering/range")
        raw[:start].decode("utf-8")
        raw[:end].decode("utf-8")

    api = load(ROOT / "contracts/openapi.json")
    for node in objects(api):
        if "$ref" not in node:
            continue
        ref = node["$ref"]
        if ref.startswith("#/"):
            target = api
            for part in ref[2:].split("/"):
                target = target[part.replace("~1", "/").replace("~0", "~")]
        else:
            require((ROOT / "contracts" / ref).is_file(), "Missing OpenAPI reference")
    for path in ROOT.rglob("*.md"):
        if ignored.intersection(path.relative_to(ROOT).parts):
            continue
        require(path.read_text().count("```") % 2 == 0, f"Unbalanced fence: {path.name}")

    validate_openapi(api, base_uri=(ROOT / "contracts/openapi.json").as_uri())

    positive = sum(case["valid"] for case in manifest)
    print(f"PASS: {len(schemas)} schemas; {positive} valid / {len(manifest)-positive} invalid examples; {len(fixtures)} desired-behavior fixtures; OpenAPI 3.1; local references and registry consistency.")
    print("Contract checks only: no security runtime, live API calls, or performance evaluation.")


if __name__ == "__main__":
    main()
