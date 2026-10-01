import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import path from "node:path";
const root = path.resolve(import.meta.dirname, "../../..");
test("provisioned Grafana dashboard shows local security and reliability metrics", async ({
  page,
}) => {
  test.skip(
    process.env.SENTINEL_MONITORING_BROWSER_TEST !== "1",
    "Optional monitoring profile",
  );
  const env = Object.fromEntries(
    readFileSync(path.join(root, ".env"), "utf8")
      .split("\n")
      .filter((line) => line && !line.startsWith("#") && line.includes("="))
      .map((line) => {
        const i = line.indexOf("=");
        return [line.slice(0, i), line.slice(i + 1)];
      }),
  );
  const origin = "http://127.0.0.1:3001";
  const login = await page.context().request.post(origin + "/login", {
    data: { user: "admin", password: env.SENTINEL_GRAFANA_PASSWORD },
  });
  expect(login.status()).toBe(200);
  let frames = 0;
  const queryErrors: string[] = [];
  page.on("response", async (response) => {
    if (!response.url().includes("/api/ds/query")) return;
    if (response.status() >= 400)
      queryErrors.push("Datasource HTTP " + response.status());
    const body = await response.json();
    for (const result of Object.values(body.results || {}) as {
      frames?: unknown[];
      error?: string;
    }[]) {
      frames += result.frames?.length || 0;
      if (result.error) queryErrors.push(result.error);
    }
  });
  await page.setViewportSize({ width: 1440, height: 1800 });
  await page.goto(
    origin + "/d/sentinel-overview?orgId=1&from=now-15m&to=now&refresh=5s",
  );
  await expect(
    page.getByText("Request outcomes / second", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("HTTP p50 / p95 / p99", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText(
      "Dependency time (inspection / generation / tools / audit)",
      { exact: true },
    ),
  ).toBeVisible();
  await expect(
    page.getByText("JVM heap / non-heap bytes", { exact: true }),
  ).toBeVisible();
  await expect(page.getByText("Query error", { exact: true })).toHaveCount(0);
  await expect.poll(() => frames).toBeGreaterThan(0);
  await expect(
    page.getByRole("button", { name: "Cancel", exact: true }),
  ).toHaveCount(0);
  await expect.poll(() => page.locator("canvas").count()).toBeGreaterThan(0);
  expect(queryErrors).toEqual([]);
  await page.evaluate(
    () =>
      new Promise<void>((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
      ),
  );
  // Repeat queries to exercise the monitored container's memory budget.
  for (let attempt = 0; attempt < 2; attempt++) {
    frames = 0;
    await page.reload();
    await expect.poll(() => frames).toBeGreaterThan(0);
    await expect(
      page.getByRole("button", { name: "Cancel", exact: true }),
    ).toHaveCount(0);
    await expect.poll(() => page.locator("canvas").count()).toBeGreaterThan(0);
    expect(queryErrors).toEqual([]);
  }
  await page.screenshot({
    path: path.join(root, "docs/phase-6/grafana.png"),
    fullPage: true,
  });
});
