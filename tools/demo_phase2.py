"""Mock-only tool proposal, independent execution, denial, and JSON-schema demo."""
import json
import urllib.error
import urllib.request
from demo import local_env


def main():
    env = local_env()
    if env.get("SENTINEL_PROVIDER", "mock") != "mock":
        raise SystemExit("Phase 2 repeatable demo requires mock mode.")
    base = "http://127.0.0.1:" + env.get("SENTINEL_PORT", "8080")

    def call(path, payload=None):
        request = urllib.request.Request(base + path, data=None if payload is None else json.dumps(payload).encode(), headers={"Authorization": "Bearer " + env["SENTINEL_DEMO_KEY"], "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as response:
            return response.code, json.load(response)

    def chat(extension):
        return {"model":env["NAVIGATOR_LLM_MODEL"], "messages":[{"role":"user","content":"Help me reset my demo password."}], "sentinel":extension}

    status, catalogue = call("/api/v1/tools")
    assert status == 200 and {t["tool_id"] for t in catalogue["tools"]} == {"kb.search","ticket.get"}
    print("PASS: authenticated tool discovery exposes the two registered read-only tools")
    status, proposal = call("/v1/chat/completions", chat({"tool_ids":["kb.search"]}))
    assert status == 200 and proposal["sentinel"]["provider_mode"] == "mock"
    proposed = proposal["choices"][0]["message"]["tool_calls"][0]["function"]
    print("PASS: model proposal validated; execution requires a separate request")
    status, result = call("/api/v1/tools/execute", {"tool_id":proposed["name"],"arguments":json.loads(proposed["arguments"])})
    assert status == 200 and result["result"]["documents"]
    print("PASS: gateway reauthorized, inspected, executed through MCP, and checked the result")
    print(json.dumps(result,indent=2))
    for title,payload,expected in [
        ("unregistered deletion denied",{"tool_id":"ticket.delete","arguments":{"ticket_id":"DEMO-1"}},403),
        ("prohibited resource denied",{"tool_id":"ticket.get","arguments":{"ticket_id":"PRIVATE-1"}},400),
        ("PII arguments blocked",{"tool_id":"kb.search","arguments":{"query":"alice@example.test"}},403),
        ("role spoof rejected",{"tool_id":"kb.search","arguments":{"query":"help"},"roles":["support_agent"]},400),
    ]:
        status, _ = call("/api/v1/tools/execute",payload)
        assert status == expected, (title,status)
        print(f"PASS: {title} — HTTP {status}")
    status, response = call("/v1/chat/completions",chat({"output_schema_id":"support-answer-v1"}))
    assert status == 200
    answer = json.loads(response["choices"][0]["message"]["content"])
    assert isinstance(answer["answer"],str) and isinstance(answer["source_ids"],list)
    print("PASS: approved structured output validated and inspected")
    print(json.dumps(answer,indent=2))


if __name__ == "__main__":
    main()
