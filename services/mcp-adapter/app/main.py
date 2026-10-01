"""Private MCP protocol adapter and read-only demonstration server. Java owns authorization."""
import asyncio
from contextlib import asynccontextmanager
import hmac
import json
import logging
import os
from pathlib import Path

import httpx2
from fastapi import FastAPI, Request
from starlette.responses import JSONResponse
from starlette.routing import Mount, Route
from jsonschema import Draft202012Validator
from mcp import Client, types
from mcp.client.streamable_http import streamable_http_client
from mcp.server import Server
from mcp.server.streamable_http_manager import StreamableHTTPSessionManager
from mcp.server.transport_security import TransportSecuritySettings

# SDK errors may contain server-provided text. The gateway audit is the metadata log.
logging.getLogger("mcp").setLevel(logging.CRITICAL)
logging.getLogger("httpx2").setLevel(logging.CRITICAL)
LIMIT = 65536


def strict_json(raw):
    def pairs(items):
        value = {}
        for key, item in items:
            if key in value:
                raise ValueError("Duplicate property")
            value[key] = item
        return value
    return json.loads(raw, object_pairs_hook=pairs, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))


class BoundedTransport(httpx2.AsyncBaseTransport):
    """JSON-only local MCP responses. No redirects, proxies, retries, or unbounded SSE."""
    def __init__(self):
        self.inner = httpx2.AsyncHTTPTransport(retries=0, trust_env=False)

    async def handle_async_request(self, request):
        if str(request.url) != "http://127.0.0.1:8100/mcp":
            raise ValueError("Unregistered destination")
        response = await self.inner.handle_async_request(request)
        try:
            chunks, size = [], 0
            async for chunk in response.aiter_bytes():
                size += len(chunk)
                if size > LIMIT:
                    raise ValueError("MCP response too large")
                chunks.append(chunk)
            body = b"".join(chunks)
            if body:
                if not response.headers.get("content-type", "").startswith("application/json"):
                    raise ValueError("Unsupported MCP content")
                strict_json(body)
            headers = {k: v for k, v in response.headers.items() if k not in {"content-encoding", "content-length"}}
            return httpx2.Response(response.status_code, headers=headers, content=body, request=request)
        finally:
            await response.aclose()

    async def aclose(self):
        await self.inner.aclose()


async def validated_call(client, registry, tool_id, arguments):
    """Pin discovery to the trusted catalogue before invoking. Ignore annotation hints."""
    if client.protocol_version != "2025-11-25" or client.instructions:
        raise ValueError("Unsupported MCP lifecycle")
    caps = client.server_capabilities.model_dump(exclude_none=True)
    if {name for name, value in caps.items() if value} - {"tools"}:
        raise ValueError("Unsupported MCP capabilities")
    listing = await client.list_tools(cache_mode="bypass")
    if listing.next_cursor or len(listing.tools) != len(registry):
        raise ValueError("Unexpected MCP catalogue")
    found = set()
    for tool in listing.tools:
        trusted = registry.get(tool.name)
        if not trusted or tool.name in found or tool.input_schema != trusted["input_schema"] or tool.output_schema != trusted["output_schema"]:
            raise ValueError("MCP schema drift")
        found.add(tool.name)
    result = await client.call_tool(tool_id, arguments, read_timeout_seconds=3)
    if result.is_error or result.result_type != "complete" or not isinstance(result.structured_content, dict) or len(result.content) != 1:
        raise ValueError("Unsupported MCP result")
    text = result.content[0]
    if text.type != "text" or strict_json(text.text) != result.structured_content:
        raise ValueError("Ambiguous MCP result")
    Draft202012Validator(registry[tool_id]["output_schema"]).validate(result.structured_content)
    if len(json.dumps(result.structured_content).encode()) > LIMIT:
        raise ValueError("MCP result too large")
    return result.structured_content


def create_app():
    key = os.environ.get("SENTINEL_MCP_KEY", "")
    if len(key) < 32:
        raise RuntimeError("Private service credential required")
    registered_path = os.environ.get("SENTINEL_REGISTRY_PATH")
    path = Path(registered_path) if registered_path else Path(__file__).resolve().parents[3] / "policies/examples/tool-registry-v1.json"
    registry = {t["tool_id"]: t for t in strict_json(path.read_bytes())["tools"]}
    counts = {name: 0 for name in registry}
    slots = asyncio.Semaphore(4)

    async def list_tools(ctx, params):
        return types.ListToolsResult(tools=[types.Tool(name=name, inputSchema=t["input_schema"], outputSchema=t["output_schema"]) for name, t in registry.items()])

    async def call_tool(ctx, params):
        tool = registry.get(params.name)
        if not tool:
            return types.CallToolResult(content=[], isError=True)
        Draft202012Validator(tool["input_schema"]).validate(params.arguments)
        counts[params.name] += 1
        if params.name == "kb.search":
            result = {"documents": [{"id": "kb-001", "text": os.environ.get("SENTINEL_DEMO_TOOL_TEXT", "Reset your demo password from account settings.")}]}
        elif params.name == "ticket.get":
            result = {"ticket_id": params.arguments["ticket_id"], "status": "open", "summary": "Demo support request awaiting review."}
        else:
            return types.CallToolResult(content=[], isError=True)
        return types.CallToolResult(content=[types.TextContent(type="text", text=json.dumps(result))], structuredContent=result)

    server = Server("SentinelLLM read-only demo", version="1", on_list_tools=list_tools, on_call_tool=call_tool)
    manager = StreamableHTTPSessionManager(server, json_response=True, max_request_body_size=LIMIT, max_sessions=16, session_idle_timeout=15,
        security_settings=TransportSecuritySettings(allowed_hosts=["127.0.0.1:8100"], allowed_origins=[]))

    @asynccontextmanager
    async def lifespan(app):
        async with manager.run():
            yield

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)

    @app.middleware("http")
    async def private_boundary(request, call_next):
        if request.url.path == "/health/live":
            return await call_next(request)
        if not hmac.compare_digest(request.headers.get("authorization", ""), "Bearer " + key):
            return JSONResponse({"error": "UNAUTHENTICATED"}, status_code=401)
        if request.url.path == "/mcp" and request.client.host != "127.0.0.1":
            return JSONResponse({"error": "LOCAL_MCP_ONLY"}, status_code=403)
        return await call_next(request)

    @app.get("/health/live")
    def health():
        return {"status": "up"}

    @app.get("/internal/v1/stats")
    def stats():
        return {"executions": counts}

    @app.post("/internal/v1/tools/execute")
    async def execute(request: Request):
        try:
            async with asyncio.timeout(4):
                data, size = bytearray(), 0
                if not request.headers.get("content-type", "").startswith("application/json"):
                    return JSONResponse({"error": "INVALID_REQUEST"}, status_code=400)
                async for chunk in request.stream():
                    size += len(chunk)
                    if size > LIMIT:
                        return JSONResponse({"error": "PAYLOAD_TOO_LARGE"}, status_code=413)
                    data.extend(chunk)
                body = strict_json(data)
                if not isinstance(body, dict) or set(body) != {"tool_id", "arguments"} or body["tool_id"] not in registry:
                    return JSONResponse({"error": "INVALID_REQUEST"}, status_code=400)
                Draft202012Validator(registry[body["tool_id"]]["input_schema"]).validate(body["arguments"])
                async with slots:
                    async with httpx2.AsyncClient(transport=BoundedTransport(), trust_env=False, follow_redirects=False, timeout=3, headers={"Authorization": "Bearer " + key}) as http:
                        transport = streamable_http_client("http://127.0.0.1:8100/mcp", http_client=http)
                        async with Client(transport, mode="legacy", read_timeout_seconds=3) as client:
                            result = await validated_call(client, registry, body["tool_id"], body["arguments"])
                return {"tool_id": body["tool_id"], "result": result}
        except Exception:
            # Nothing from the server, arguments, or SDK exception is echoed or logged.
            return JSONResponse({"error": "OUTCOME_UNCERTAIN"}, status_code=503)

    class McpEndpoint:
        async def __call__(self, scope, receive, send):
            await manager.handle_request(scope, receive, send)
    app.router.routes.append(Mount("/", routes=[Route("/mcp", endpoint=McpEndpoint(), methods=["GET", "POST", "DELETE"])]))
    app.state.registry = registry
    app.state.counts = counts
    return app
