import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e",
  timeout: 60000,
  workers: 1,
  use: {
    baseURL: process.env.BASE_URL || "http://127.0.0.1:5173",
    headless: true,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  reporter: [
    ["list"],
    ["json", { outputFile: "../artifacts/browser-results.json" }],
  ],
});
