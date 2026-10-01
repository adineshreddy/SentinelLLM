import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e",
  workers: 1,
  retries: 0,
  timeout: 45000,
  use: {
    baseURL: process.env.SENTINEL_CONSOLE_TEST_URL || "http://127.0.0.1:3000",
    viewport: { width: 1440, height: 1000 },
    trace: "off",
    screenshot: "off",
    video: "off",
  },
  reporter: "list",
});
