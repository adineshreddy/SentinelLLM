import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import path from "node:path";

// Run explicitly with the classifier-enabled stack; default UI suite stays rule-only.
test("opt-in ML findings are rendered from real gateway inspection", async ({
  page,
}) => {
  test.skip(
    process.env.SENTINEL_ML_BROWSER_TEST !== "1",
    "Explicit classifier-enabled browser run",
  );
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
  const login = await page.context().request.post(origin + "/api/login", {
    headers: { Origin: origin },
    data: {
      username: "operator",
      password: env.SENTINEL_CONSOLE_OPERATOR_PASSWORD,
    },
  });
  expect(login.status()).toBe(200);
  await page.goto(origin);
  await expect(
    page.getByRole("heading", { name: "Your AI perimeter, in view." }),
  ).toBeVisible();
  await expect(page.getByText("Working with the gateway…")).toHaveCount(0);
  await page.getByRole("button", { name: "Playground", exact: true }).click();
  await page
    .getByRole("button", { name: "Authority spoof (ML)", exact: true })
    .click();
  await page.getByRole("button", { name: "Preview inspection" }).click();
  await expect(page.getByText("Working with the gateway…")).toHaveCount(0);
  await expect(page.locator("pre")).toContainText('"action": "deny"');
  await expect(page.locator("pre")).toContainText("ML.PROMPT_INJECTION");
  await expect(page.locator("pre")).toContainText('"score": 0.95721944');
  await expect(page.locator("pre")).toContainText("rules-v1+tfidf-pi-v1.");
  await page.screenshot({
    path: path.join(root, "docs/phase-5/ml-playground.png"),
    fullPage: true,
  });
});
