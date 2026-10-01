import { test } from "node:test";
import http from "node:http";
import assert from "node:assert/strict";
import { createConsole, settings } from "../server.mjs";
const secret = "synthetic-test-password-12345678901234567890";
const op = "00000000-0000-4000-8000-000000000001";
async function harness(t, options = {}) {
  const calls = [];
  let clock = 10000;
  const config = {
    origin: "http://127.0.0.1:3000",
    gateway: "http://gateway:8080",
    credentials: { operator: secret, viewer: secret + "viewer" },
    keys: {
      operator: "operator-server-key",
      viewer: "viewer-server-key",
      work: "work-server-key",
    },
    model: "gpt-oss-120b",
    provider: "mock",
    ...options.config,
  };
  const fetcher =
    options.fetcher ||
    (async (url, init) => {
      calls.push({ url, ...init });
      return new Response(JSON.stringify({ ok: true }), {
        headers: { "x-sentinel-operation-id": op },
      });
    });
  const server = createConsole(config, {
    now: () => clock,
    fetcher,
    sessionMs: 60000,
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  t.after(() => new Promise((r) => server.close(r)));
  const base = "http://127.0.0.1:" + server.address().port;
  const call = (
    route,
    {
      body,
      method,
      cookie,
      csrf,
      origin = config.origin,
      host = "127.0.0.1:3000",
    } = {},
  ) =>
    new Promise((resolve, reject) => {
      const req = http.request(
        base + route,
        {
          method: method || (body ? "POST" : "GET"),
          headers: {
            Host: host,
            ...(origin ? { Origin: origin } : {}),
            ...(cookie ? { Cookie: cookie } : {}),
            ...(csrf ? { "X-CSRF-Token": csrf } : {}),
            ...(body ? { "Content-Type": "application/json" } : {}),
          },
        },
        (res) => {
          const chunks = [];
          res.on("data", (c) => chunks.push(c));
          res.on("end", () =>
            resolve(
              new Response(Buffer.concat(chunks), {
                status: res.statusCode,
                headers: res.headers,
              }),
            ),
          );
        },
      );
      req.on("error", reject);
      req.end(body ? JSON.stringify(body) : undefined);
    });
  const login = async (role = "operator") => {
    const r = await call("/api/login", {
      body: {
        username: role,
        password: role === "viewer" ? secret + "viewer" : secret,
      },
    });
    assert.equal(r.status, 200);
    return {
      cookie: r.headers.get("set-cookie").split(";")[0],
      csrf: (await r.json()).csrf,
    };
  };
  return {
    call,
    login,
    calls,
    advance: (ms) => {
      clock += ms;
    },
  };
}
test("protected routes reject missing sessions before contacting gateway", async (t) => {
  const h = await harness(t);
  for (const route of [
    "/api/config",
    "/api/events",
    "/api/status",
    "/api/operations/" + op,
  ])
    assert.equal((await h.call(route)).status, 401);
  assert.equal(h.calls.length, 0);
});
test("login rejects incorrect password and username without role escalation", async (t) => {
  const h = await harness(t);
  for (const username of [
    "operator",
    "support_agent",
    "constructor",
    "__proto__",
  ])
    assert.equal(
      (
        await h.call("/api/login", {
          body: { username, password: "incorrect" },
        })
      ).status,
      401,
    );
});
test("HttpOnly session, csrf, and expiry expose no gateway credentials", async (t) => {
  const h = await harness(t);
  const r = await h.call("/api/login", {
    body: { username: "operator", password: secret },
  });
  assert.match(r.headers.get("set-cookie"), /HttpOnly; SameSite=Strict/);
  const text = await r.text();
  for (const term of [
    secret,
    "operator-server-key",
    "viewer-server-key",
    "work-server-key",
  ])
    assert.ok(!text.includes(term));
  const auth = { cookie: r.headers.get("set-cookie").split(";")[0] };
  assert.equal((await h.call("/api/session", auth)).status, 200);
  h.advance(60001);
  assert.equal((await h.call("/api/session", auth)).status, 401);
});
test("viewer uses viewer key for reads and cannot write or execute", async (t) => {
  const h = await harness(t);
  const auth = await h.login("viewer");
  assert.equal((await h.call("/api/config", auth)).status, 200);
  assert.equal(h.calls[0].headers.Authorization, "Bearer viewer-server-key");
  for (const route of [
    "/api/config",
    "/api/inspect",
    "/api/chat",
    "/api/tool",
    "/api/demo/support",
    "/api/demo/reset",
    "/api/demo/seed",
  ])
    assert.equal(
      (
        await h.call(route, {
          ...auth,
          body: {},
          method: route === "/api/config" ? "PUT" : "POST",
        })
      ).status,
      403,
    );
  assert.equal(h.calls.length, 1);
});
test("cross-origin, missing origin, csrf, and hostile host requests cannot execute", async (t) => {
  const h = await harness(t);
  const auth = await h.login();
  for (const extra of [
    { origin: "http://evil.test" },
    { origin: null },
    { csrf: "incorrect" },
    { csrf: "é".repeat(43) },
    { host: "evil.test" },
  ])
    assert.equal(
      (await h.call("/api/chat", { ...auth, body: {}, ...extra })).status,
      403,
    );
  assert.equal(h.calls.length, 0);
});
test("login is rate limited with a bounded time window", async (t) => {
  const h = await harness(t);
  for (let i = 0; i < 10; i++)
    assert.equal(
      (
        await h.call("/api/login", {
          body: { username: "operator", password: "wrong" },
        })
      ).status,
      401,
    );
  assert.equal(
    (
      await h.call("/api/login", {
        body: { username: "operator", password: secret },
      })
    ).status,
    429,
  );
  h.advance(60001);
  await h.login();
});
test("logout invalidates the previous session", async (t) => {
  const h = await harness(t);
  const auth = await h.login();
  assert.equal(
    (await h.call("/api/logout", { ...auth, body: {} })).status,
    200,
  );
  assert.equal((await h.call("/api/config", auth)).status, 401);
});
test("only allowlisted routes and query fields reach fixed upstream", async (t) => {
  const h = await harness(t);
  const auth = await h.login();
  for (const route of [
    "/api/events?tenant_id=other",
    "/api/events?limit=1&limit=2",
    "/api/config?url=http://evil.test",
    "/api/proxy?url=http://evil.test",
    "/api/operations/not-a-uuid",
  ])
    assert.ok([400, 404].includes((await h.call(route, auth)).status));
  assert.equal(h.calls.length, 0);
  assert.equal(
    (await h.call("/api/events?limit=10&action=deny", auth)).status,
    200,
  );
  assert.equal(
    h.calls[0].url,
    "http://gateway:8080/api/v1/management/events?limit=10&action=deny",
  );
});
test("operator management updates use the operator key", async (t) => {
  const h = await harness(t);
  const auth = await h.login();
  await h.call("/api/config", {
    ...auth,
    body: { expected_revision: 5 },
    method: "PUT",
  });
  assert.equal(h.calls[0].headers.Authorization, "Bearer operator-server-key");
  assert.equal(h.calls[0].method, "PUT");
});
test("execution pins model and work identity server-side", async (t) => {
  const h = await harness(t);
  const auth = await h.login();
  const r = await h.call("/api/chat", {
    ...auth,
    body: { model: "attacker-model", messages: [] },
  });
  assert.equal(r.status, 200);
  const result = await r.json();
  assert.equal(result.operation_id, op);
  assert.equal(JSON.parse(h.calls[0].body).model, "gpt-oss-120b");
  assert.equal(h.calls[0].headers.Authorization, "Bearer work-server-key");
});
test("hosted generation demos are blocked while previews remain available", async (t) => {
  const h = await harness(t, { config: { provider: "navigator" } });
  const auth = await h.login();
  for (const route of [
    "/api/chat",
    "/api/tool",
    "/api/demo/seed",
    "/api/demo/support",
  ])
    assert.equal((await h.call(route, { ...auth, body: {} })).status, 409);
  assert.equal(h.calls.length, 0);
  assert.equal(
    (await h.call("/api/inspect", { ...auth, body: { segments: [] } })).status,
    200,
  );
});
test("upstream errors are sanitized and oversized bodies fail closed", async (t) => {
  let mode = "error";
  const h = await harness(t, {
    fetcher: async () =>
      mode === "error"
        ? new Response(
            JSON.stringify({
              error: {
                code: "POLICY_DENIED",
                message: "sensitive raw content",
              },
            }),
            { status: 403 },
          )
        : new Response("x".repeat(524289)),
  });
  const auth = await h.login();
  const r = await h.call("/api/chat", { ...auth, body: {} });
  assert.deepEqual((await r.json()).data, { error: { code: "POLICY_DENIED" } });
  mode = "large";
  assert.equal((await h.call("/api/config", auth)).status, 502);
});
test("support workflow stops on a denied boundary without executing tools", async (t) => {
  let count = 0;
  const h = await harness(t, {
    fetcher: async () => {
      count++;
      return new Response(
        JSON.stringify({ error: { code: "POLICY_DENIED" } }),
        { status: 403 },
      );
    },
  });
  const auth = await h.login();
  const r = await h.call("/api/demo/support", { ...auth, body: {} });
  assert.equal(r.status, 200);
  assert.equal((await r.json()).steps.length, 1);
  assert.equal(count, 1);
});
test("support workflow rejects unknown or multiple proposals", async (t) => {
  let count = 0;
  const h = await harness(t, {
    fetcher: async () => {
      count++;
      return new Response(
        JSON.stringify({
          choices: [
            {
              message: {
                tool_calls: [
                  { function: { name: "ticket.delete", arguments: "{}" } },
                ],
              },
            },
          ],
        }),
      );
    },
  });
  const auth = await h.login();
  assert.equal(
    (await h.call("/api/demo/support", { ...auth, body: {} })).status,
    502,
  );
  assert.equal(count, 1);
});
test("support workflow reauthorizes and sends safe documents back through RAG inspection", async (t) => {
  const calls = [];
  const h = await harness(t, {
    fetcher: async (url, init) => {
      calls.push({ url, body: JSON.parse(init.body) });
      const data =
        calls.length === 1
          ? {
              choices: [
                {
                  message: {
                    tool_calls: [
                      {
                        function: {
                          name: "kb.search",
                          arguments: '{"query":"password"}',
                        },
                      },
                    ],
                  },
                },
              ],
            }
          : calls.length === 2
            ? {
                result: {
                  documents: [{ id: "kb-001", text: "Reset your password." }],
                },
              }
            : {
                choices: [
                  {
                    message: {
                      content: '{"answer":"Reset","source_ids":["kb-001"]}',
                    },
                  },
                ],
              };
      return new Response(JSON.stringify(data));
    },
  });
  const auth = await h.login();
  const r = await h.call("/api/demo/support", { ...auth, body: {} });
  assert.equal((await r.json()).steps.length, 3);
  assert.match(calls[1].url, /tools\/execute$/);
  assert.deepEqual(calls[1].body, {
    tool_id: "kb.search",
    arguments: { query: "password" },
  });
  assert.equal(calls[2].body.sentinel.output_schema_id, "support-answer-v1");
  assert.equal(calls[2].body.sentinel.context[0].text, "Reset your password.");
});
test("oversized client body is rejected before upstream dispatch", async (t) => {
  const h = await harness(t);
  const auth = await h.login();
  assert.equal(
    (
      await h.call("/api/chat", {
        ...auth,
        body: { messages: ["x".repeat(262144)] },
      })
    ).status,
    413,
  );
  assert.equal(h.calls.length, 0);
});
test("invalid settings fail without embedding secrets in errors", () => {
  assert.throws(() => settings({}), /provision/);
  assert.throws(
    () => settings({ SENTINEL_CONSOLE_ORIGIN: "http://x/" }),
    /URL/,
  );
});
