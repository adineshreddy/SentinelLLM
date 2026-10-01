import asyncio
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.request
import urllib.error

import pytest

ROOT = Path(__file__).resolve().parents[3]
KEY = "synthetic-test-service-key-not-a-secret-12345"
spec = importlib.util.spec_from_file_location("adapter", ROOT / "services/mcp-adapter/app/main.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

@pytest.fixture(scope="module")
def server():
    sock = socket.socket()
    try:
        sock.bind(("127.0.0.1", 8100))
    except OSError:
        pytest.fail("Port 8100 must be free for isolated MCP transport tests")
    finally:
        sock.close()
    env = dict(os.environ, SENTINEL_MCP_KEY=KEY)
    proc = subprocess.Popen([sys.executable, "-m", "uvicorn", "app.main:create_app", "--factory", "--host", "127.0.0.1", "--port", "8100", "--no-access-log", "--log-level", "critical"], cwd=ROOT / "services/mcp-adapter", env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(100):
            try:
                urllib.request.urlopen("http://127.0.0.1:8100/health/live", timeout=.2)
                break
            except OSError:
                if proc.poll() is not None:
                    pytest.fail("MCP fixture server failed to start")
                time.sleep(.05)
        else:
            pytest.fail("MCP fixture startup timed out")
        yield
    finally:
        proc.terminate()
        proc.wait(timeout=5)


def request(path, body=None, key=KEY, headers=None):
    req = urllib.request.Request("http://127.0.0.1:8100" + path, data=None if body is None else json.dumps(body).encode(), headers={"Authorization": "Bearer " + key, "Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=8) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as ex:
        raw = ex.read()
        try: return ex.code, json.loads(raw)
        except ValueError: return ex.code, {"error":"NON_JSON"}


def test_real_streamable_http_lifecycle_and_call(server):
    status, body = request("/internal/v1/tools/execute", {"tool_id": "ticket.get", "arguments": {"ticket_id": "DEMO-42"}})
    assert status == 200, body
    assert body["result"]["ticket_id"] == "DEMO-42"
    assert request("/internal/v1/stats")[1]["executions"]["ticket.get"] == 1


def test_schema_and_unknown_tool_no_execution(server):
    before = request("/internal/v1/stats")[1]
    assert request("/internal/v1/tools/execute", {"tool_id": "ticket.delete", "arguments": {}})[0] == 400
    assert request("/internal/v1/tools/execute", {"tool_id": "ticket.get", "arguments": {"ticket_id": "PRIVATE-1"}})[0] == 503
    assert request("/internal/v1/stats")[1] == before


def test_private_credential_and_origin(server):
    assert request("/internal/v1/stats", key="invalid")[0] == 401
    status, body = request("/mcp", {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-11-25", "capabilities": {}, "clientInfo": {"name": "test", "version": "1"}}}, headers={"Origin": "https://evil.invalid", "Accept": "application/json, text/event-stream"})
    assert status == 403


def test_strict_json():
    with pytest.raises(ValueError):
        module.strict_json('{"a":1,"a":2}')
    with pytest.raises(ValueError):
        module.strict_json('{"a":NaN}')


def test_schema_drift_never_calls():
    from mcp import types
    class Fake:
        protocol_version = "2025-11-25"
        instructions = None
        server_capabilities = types.ServerCapabilities(tools=types.ToolsCapability())
        calls = 0
        async def list_tools(self, **kwargs):
            return types.ListToolsResult(tools=[types.Tool(name="kb.search", inputSchema={"type": "object"})])
        async def call_tool(self, *args, **kwargs):
            self.calls += 1
    client = Fake()
    registry = json.loads((ROOT / "policies/examples/tool-registry-v1.json").read_text())
    with pytest.raises(ValueError):
        asyncio.run(module.validated_call(client, {t["tool_id"]:t for t in registry["tools"]}, "kb.search", {"query":"help"}))
    assert client.calls == 0


def test_container_registry_path_is_lazy(monkeypatch):
    monkeypatch.setenv("SENTINEL_MCP_KEY", KEY)
    monkeypatch.setenv("SENTINEL_REGISTRY_PATH", str(ROOT / "policies/examples/tool-registry-v1.json"))
    monkeypatch.setattr(module, "__file__", "/app/app/main.py")
    assert set(module.create_app().state.registry) == {"kb.search","ticket.get"}


@pytest.mark.parametrize("kind",["image","ambiguous","error","nonobject"])
def test_unsupported_result_content_is_rejected(kind):
    from mcp import types
    registry={t["tool_id"]:t for t in json.loads((ROOT / "policies/examples/tool-registry-v1.json").read_text())["tools"]}
    value={"documents":[]}
    content=[types.TextContent(type="text",text=json.dumps(value))]
    if kind == "image": content=[types.ImageContent(type="image",data="eA==",mimeType="image/png")]
    if kind == "ambiguous": content=[types.TextContent(type="text",text='{"documents":[{"id":"x","text":"other"}]}')]
    class Fake:
        protocol_version="2025-11-25"
        instructions=None
        server_capabilities=types.ServerCapabilities(tools=types.ToolsCapability())
        async def list_tools(self,**kwargs):
            return types.ListToolsResult(tools=[types.Tool(name=name,inputSchema=t["input_schema"],outputSchema=t["output_schema"]) for name,t in registry.items()])
        async def call_tool(self,*args,**kwargs):
            return types.CallToolResult(content=content,structuredContent=[] if kind=="nonobject" else value,isError=kind=="error")
    with pytest.raises(ValueError):asyncio.run(module.validated_call(Fake(),registry,"kb.search",{"query":"safe"}))


@pytest.mark.parametrize("kind",["oversize","nonjson","destination"])
def test_bounded_transport(kind):
    import httpx2
    async def check():
        transport=module.BoundedTransport()
        await transport.inner.aclose()
        transport.inner=httpx2.MockTransport(lambda request:httpx2.Response(200,headers={"content-type":"text/plain" if kind=="nonjson" else "application/json"},content=b'"'+b'x'*(module.LIMIT if kind=="oversize" else 10)+b'"'))
        url="http://evil.invalid/mcp" if kind=="destination" else "http://127.0.0.1:8100/mcp"
        async with httpx2.AsyncClient(transport=transport) as client:
            with pytest.raises(ValueError):await client.get(url)
    asyncio.run(check())
