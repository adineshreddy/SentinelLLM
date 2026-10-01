import { test, expect, Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import path from "node:path";
const root = path.resolve(import.meta.dirname, "../../..");
const env = Object.fromEntries(
  readFileSync(path.join(root, ".env"), "utf8")
    .split("\n")
    .filter((l) => l && !l.startsWith("#") && l.includes("="))
    .map((l) => {
      const i = l.indexOf("=");
      return [l.slice(0, i), l.slice(i + 1)];
    }),
);
const origin =
  process.env.SENTINEL_CONSOLE_TEST_URL ||
  "http://127.0.0.1:" + (env.SENTINEL_CONSOLE_PORT || "3000");
async function login(page: Page, role = "operator") {
  // Request context stores HttpOnly cookies; no credentials enter recordings or browser storage.
  const r = await page.context().request.post(origin + "/api/login", {
    headers: { Origin: origin },
    data: {
      username: role,
      password: env["SENTINEL_CONSOLE_" + role.toUpperCase() + "_PASSWORD"],
    },
  });
  expect(r.status()).toBe(200);
  const session = await r.json();
  await page.goto(origin);
  await expect(
    page.getByRole("heading", { name: "Your AI perimeter, in view." }),
  ).toBeVisible();
  // The shell heading renders before the initial two-request refresh finishes.
  // Wait for real configuration so direct API checks do not become a third
  // concurrent request and correctly hit the BFF's admission limit.
  await expect(
    page
      .locator(".metrics article")
      .filter({ hasText: "Active policy" })
      .getByRole("heading"),
  ).toHaveText(/^v\d+$/);
  await expect(page.getByRole("status")).toHaveCount(0);
  return session;
}
async function configuration(page: Page) {
  const response = await page.context().request.get(origin + "/api/config");
  expect(response.status()).toBe(200);
  const value = await response.json();
  expect(value.policy).toBeDefined();
  expect(value.registry).toBeDefined();
  expect(typeof value.revision).toBe("number");
  return value;
}
async function idle(page: Page) {
  await expect(page.getByText("Working with the gateway…")).toHaveCount(0);
}
test("operator console runs real checks, reads traces, and completes the guarded MCP workflow", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await login(page);
  await page.getByRole("button", { name: "Seed demo evidence" }).click();
  await expect(page.getByRole("status")).toContainText("Four synthetic");
  await page.screenshot({
    path: path.join(root, "docs/phase-4/overview.png"),
    fullPage: true,
  });
  await page.getByRole("button", { name: "Playground", exact: true }).click();
  await page
    .getByRole("button", { name: "Prompt injection", exact: true })
    .click();
  await page.getByRole("button", { name: "Preview inspection" }).click();
  await idle(page);
  await expect(page.locator("pre")).toContainText('"action": "deny"');
  await page.getByRole("button", { name: "Run enforced request" }).click();
  await idle(page);
  await expect(page.locator("pre")).toContainText("POLICY_DENIED");
  await page.getByRole("button", { name: /Inspect operation/ }).click();
  await expect(page.getByRole("dialog")).toContainText("blocked");
  await page.screenshot({
    path: path.join(root, "docs/phase-4/blocked-trace.png"),
    fullPage: true,
  });
  await page.getByRole("button", { name: "Close trace" }).click();
  await page.getByRole("button", { name: "PII exposure", exact: true }).click();
  await page.getByRole("button", { name: "Preview inspection" }).click();
  await idle(page);
  await expect(page.locator("pre")).toContainText('"action": "redact"');
  await page
    .getByRole("button", { name: "Poisoned retrieval", exact: true })
    .click();
  await page.getByRole("button", { name: "Run enforced request" }).click();
  await idle(page);
  await expect(page.locator("pre")).toContainText("POLICY_DENIED");
  await page
    .getByRole("button", { name: "Forbidden tool", exact: true })
    .click();
  await page.getByRole("button", { name: "Attempt forbidden tool" }).click();
  await idle(page);
  await expect(page.locator("pre")).toContainText("POLICY_DENIED");
  await page
    .getByRole("button", { name: "Authority spoof (ML)", exact: true })
    .click();
  await expect(
    page.getByText(/This scenario demonstrates the opt-in local classifier/),
  ).toBeVisible();
  await page.getByRole("button", { name: "Preview inspection" }).click();
  await idle(page);
  await expect(page.locator("pre")).toContainText('"action": "allow"');
  await page.getByRole("button", { name: "Events", exact: true }).click();
  await idle(page);
  await page.getByLabel("Action", { exact: true }).selectOption("deny");
  await page.getByRole("button", { name: "Apply filters" }).click();
  await idle(page);
  const rows = page.locator("tbody tr");
  expect(await rows.count()).toBeGreaterThan(0);
  for (const row of await rows.all())
    await expect(row.locator("td").first()).toContainText("deny");
  await page
    .getByRole("button", { name: "Support agent", exact: true })
    .click();
  await page.getByRole("button", { name: "Run guarded workflow" }).click();
  await idle(page);
  await expect(page.locator(".agent-steps .completed")).toHaveCount(3);
  await expect(page.locator(".agent-steps pre").last()).toContainText(
    "source_ids",
  );
  await page.screenshot({
    path: path.join(root, "docs/phase-4/support-agent.png"),
    fullPage: true,
  });
  const text = await page.locator("body").innerText();
  for (const [name, value] of Object.entries(env))
    if (value && /KEY$|PASSWORD$/.test(name))
      expect(text.includes(value)).toBe(false);
  expect(errors).toEqual([]);
});
test("viewer can inspect evidence but both UI and server reject mutation and execution", async ({
  page,
}) => {
  const session = await login(page, "viewer");
  await expect(
    page.getByRole("button", { name: "Seed demo evidence" }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "Policies", exact: true }).click();
  await idle(page);
  await expect(
    page.getByRole("button", { name: /Publish revision/ }),
  ).toBeDisabled();
  await expect(page.getByLabel("pii prompt action")).toBeDisabled();
  for (const route of [
    "/api/config",
    "/api/chat",
    "/api/tool",
    "/api/demo/reset",
    "/api/demo/support",
  ]) {
    const r = await page.context().request.fetch(origin + route, {
      method: route === "/api/config" ? "PUT" : "POST",
      headers: { Origin: origin, "X-CSRF-Token": session.csrf },
      data: {},
    });
    expect(r.status()).toBe(403);
  }
  await page.getByRole("button", { name: "Events", exact: true }).click();
  await idle(page);
  await expect(
    page.getByRole("heading", { name: "Follow every decision." }),
  ).toBeVisible();
});
test("policy publication changes enforcement, conflicts are rejected, and restore preserves history", async ({
  page,
}) => {
  const session = await login(page);
  const headers = { Origin: origin, "X-CSRF-Token": session.csrf };
  const original = await configuration(page);
  try {
    await page.getByRole("button", { name: "Policies", exact: true }).click();
    await idle(page);
    await page.getByLabel("pii prompt action").selectOption("deny");
    await page.getByRole("button", { name: /Publish revision/ }).click();
    await expect(page.getByRole("status")).toContainText("Published revision");
    const current = await configuration(page);
    expect(current.revision).toBe(original.revision + 1);
    const stale = await page.context().request.put(origin + "/api/config", {
      headers,
      data: {
        expected_revision: original.revision,
        policy: current.policy,
        registry: current.registry,
      },
    });
    expect(stale.status()).toBe(409);
    await page.getByRole("button", { name: "Playground", exact: true }).click();
    await page
      .getByRole("button", { name: "PII exposure", exact: true })
      .click();
    await page.getByRole("button", { name: "Preview inspection" }).click();
    await idle(page);
    await expect(page.locator("pre")).toContainText('"action": "deny"');
    await page.getByRole("button", { name: "Policies", exact: true }).click();
    await idle(page);
    await page.getByRole("button", { name: "Restore demo defaults" }).click();
    await expect(page.getByRole("status")).toContainText("History retained");
    await page.screenshot({
      path: path.join(root, "docs/phase-4/policies.png"),
      fullPage: true,
    });
  } finally {
    const current = await configuration(page);
    original.policy.version = current.revision + 1;
    original.registry.version = current.revision + 1;
    const restore = await page.context().request.put(origin + "/api/config", {
      headers,
      data: {
        expected_revision: current.revision,
        policy: original.policy,
        registry: original.registry,
      },
    });
    expect(restore.status()).toBe(200);
  }
});
test("login page and responsive console have no horizontal document overflow", async ({
  page,
}) => {
  await page.goto(origin);
  await expect(
    page.getByRole("heading", { name: "Welcome back." }),
  ).toBeVisible();
  await page.screenshot({
    path: path.join(root, "docs/phase-4/login.png"),
    fullPage: true,
  });
  await login(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: path.join(root, "docs/phase-4/mobile.png"),
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.getByRole("button", { name: "Policies", exact: true }).click();
  await idle(page);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});
test("login form creates and revokes a session without browser credential storage", async ({
  page,
}) => {
  await page.goto(origin);
  await page.getByLabel("Password", { exact: true }).fill("incorrect-password");
  await page.getByRole("button", { name: "Open console" }).click();
  await expect(page.getByRole("alert")).toHaveText("INVALID_LOGIN");
  await page
    .getByLabel("Password", { exact: true })
    .fill(env.SENTINEL_CONSOLE_OPERATOR_PASSWORD);
  await page.getByRole("button", { name: "Open console" }).click();
  await expect(
    page.getByRole("heading", { name: "Your AI perimeter, in view." }),
  ).toBeVisible();
  await idle(page);
  expect(
    await page.evaluate(() => ({
      local: localStorage.length,
      session: sessionStorage.length,
      readableCookies: document.cookie,
    })),
  ).toEqual({ local: 0, session: 0, readableCookies: "" });
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(
    page.getByRole("heading", { name: "Welcome back." }),
  ).toBeVisible();
  const r = await page.context().request.get(origin + "/api/config");
  expect(r.status()).toBe(401);
});
