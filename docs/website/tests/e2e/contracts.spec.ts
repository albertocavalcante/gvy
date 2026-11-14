// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { test, expect } from "./fixtures";
import { pages } from "../../content-map.mjs";
import { readFileSync } from "node:fs";
const inventory = JSON.parse(
  readFileSync(new URL("../../route-inventory.json", import.meta.url), "utf8"),
) as Record<string, string[]>;

const routes = [
  "",
  ...pages.map(([, slug]) => `${slug.replace(/\/index$/, "")}/`),
];
test("all published routes and legacy fragments resolve", async ({ page }) => {
  expect(new Set(routes).size).toBe(pages.length + 1);
  for (const route of routes) {
    const response = await page.goto(route);
    expect(response?.status(), route).toBe(200);
    await expect(page.locator("h1").first(), route).toBeVisible();
    for (const id of inventory[`/${route}`] ?? [])
      await expect(page.locator(`[id="${id}"]`), `${route}#${id}`).toHaveCount(
        1,
      );
  }
  const response = await page.goto("not-a-real-page/");
  expect(response?.status()).toBe(404);
});
test("production search finds five journeys and restores focus", async ({
  page,
}) => {
  await page.goto("");
  const trigger = page.locator("button[data-open-modal]");
  await trigger.click();
  const dialog = page.getByRole("dialog");
  const input = dialog.getByRole("textbox");
  for (const [query, target] of [
    ["installation", "getting-started/installation/"],
    ["Java", "guides/java/"],
    ["Jenkins", "guides/jenkins/"],
    ["Spock", "guides/spock/"],
    ["troubleshooting", "guides/troubleshooting/"],
  ]) {
    await input.fill(query);
    await expect(dialog.locator(`a[href*="/${target}"]`).first()).toBeVisible();
  }
  await page.keyboard.press("Escape");
  await expect(dialog).not.toBeVisible();
  await expect(trigger).toBeFocused();
});
test("home retains essential guidance without JavaScript", async ({
  browser,
}) => {
  const context = await browser.newContext({
    javaScriptEnabled: false,
    viewport: { width: 360, height: 900 },
  });
  try {
    const page = await context.newPage();
    await page.goto(
      process.env.GVY_E2E_BASE_URL ?? "http://127.0.0.1:43219/gvy/",
    );
    await expect(page.getByRole("heading", { level: 1 })).toHaveText(
      "Groovy language tools for your editor.",
    );
    await expect(
      page.getByRole("link", { name: /Install gvy/ }).first(),
    ).toBeVisible();
    await expect(
      page
        .getByText("brew install albertocavalcante/tap/gvy", { exact: false })
        .first(),
    ).toBeVisible();
    await expect(page.locator(".release-notice")).toContainText("v0.5.4");
    await expect(page.locator(".release-notice")).toContainText("v0.4.8");
  } finally {
    await context.close();
  }
});

test("keyboard can reach and scroll overflowing code and tables without JavaScript", async ({
  browser,
}) => {
  const context = await browser.newContext({
    javaScriptEnabled: false,
    viewport: { width: 360, height: 900 },
  });
  try {
    const page = await context.newPage();
    const base = process.env.GVY_E2E_BASE_URL ?? "http://127.0.0.1:43219/gvy/";
    for (const [route, selector] of [
      ["getting-started/installation/", "pre"],
      ["reference/settings/", "table"],
    ]) {
      await page.goto(new URL(route, base).href);
      const scrolling = page.locator(selector).filter({ visible: true });
      const count = await scrolling.count();
      let checked = 0;
      for (let i = 0; i < count; i++) {
        const item = scrolling.nth(i);
        if (!(await item.evaluate((el) => el.scrollWidth > el.clientWidth + 1)))
          continue;
        let reached = false;
        for (let tab = 0; tab < 250; tab++) {
          await page.keyboard.press("Tab");
          if (await item.evaluate((el) => el === document.activeElement)) {
            reached = true;
            break;
          }
        }
        expect(reached, `${route} ${selector} must be reachable by Tab`).toBe(
          true,
        );
        const before = await item.evaluate((el) => el.scrollLeft);
        await page.keyboard.press("ArrowRight");
        await expect
          .poll(() => item.evaluate((el) => el.scrollLeft))
          .toBeGreaterThan(before);
        checked++;
        break;
      }
      expect(
        checked,
        `expected an overflowing ${selector} on ${route}`,
      ).toBeGreaterThan(0);
    }
  } finally {
    await context.close();
  }
});

test("copy button writes the install command to the clipboard", async ({
  page,
  context,
}) => {
  test.skip(
    process.env.GVY_E2E_CLIPBOARD !== "1",
    "Real clipboard access requires explicit opt-in.",
  );
  await page.goto("");
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  const block = page
    .locator(".expressive-code")
    .filter({ hasText: "brew install albertocavalcante/tap/gvy" })
    .first();
  await block.getByRole("button", { name: /copy/i }).click();
  await expect
    .poll(() => page.evaluate(() => navigator.clipboard.readText()))
    .toContain("brew install albertocavalcante/tap/gvy");
});
