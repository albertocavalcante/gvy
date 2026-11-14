// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from "@playwright/test";
import { execFileSync } from "node:child_process";

const external = process.env.GVY_E2E_BASE_URL;
const chrome = process.env.GVY_E2E_SYSTEM_CHROME === "1";
export default defineConfig({
  metadata: {
    source: execFileSync("git", ["rev-parse", "HEAD"], {
      encoding: "utf8",
    }).trim(),
    node: process.version,
    bun: execFileSync("bun", ["--version"], { encoding: "utf8" }).trim(),
    platform: process.platform,
    browserChannel: chrome ? "system Chrome" : "bundled Chromium",
  },
  testDir: "./tests/e2e",
  workers: 1,
  fullyParallel: false,
  retries: 0,
  reporter: [
    ["list"],
    ["html", { outputFolder: "playwright-report", open: "never" }],
  ],
  use: {
    baseURL: external ?? "http://127.0.0.1:43219/gvy/",
    browserName: "chromium",
    channel: chrome ? "chrome" : undefined,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: external
    ? undefined
    : {
        command: "node tests/e2e/host.mjs",
        url: "http://127.0.0.1:43219/gvy/",
        reuseExistingServer: false,
        timeout: 30000,
      },
  projects: [
    { name: "contracts", testMatch: /contracts\.spec\.ts/ },
    ...[360, 768, 1440].flatMap((width) =>
      (["light", "dark"] as const).map((colorScheme) => ({
        name: `chromium-${width}-${colorScheme}`,
        testMatch: /matrix\.spec\.ts/,
        use: {
          viewport: { width, height: 900 },
          colorScheme,
          reducedMotion: "reduce" as const,
        },
      })),
    ),
  ],
});

// TODO(#2020): Add reviewed Linux visual baselines and Firefox/WebKit critical journeys.
