import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "e2e",
  timeout: 60_000,
  workers: 1,
  use: {
    baseURL: "http://127.0.0.1:8123",
    launchOptions: { executablePath: process.env.CHROMIUM_PATH || "/opt/pw-browsers/chromium-1194/chrome-linux/chrome" },
    trace: "retain-on-failure",
  },
  webServer: { command: "../scripts/e2e-server.sh", url: "http://127.0.0.1:8123/healthz", reuseExistingServer: false,
    // loading a local GGUF model at start-up can take minutes on a busy CPU
    timeout: process.env.CAMPUS_LLM_PROVIDER ? 300_000 : 60_000 },
});
