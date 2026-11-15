// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { test, expect } from "./fixtures";
import type { Page, Route } from "@playwright/test";

async function withDelayedSearch(
  page: Page,
  run: (release: () => void) => Promise<void>,
) {
  const modulePattern = "**/_astro/ui-core.*.js";
  let releaseModule!: () => void;
  let markRequested!: () => void;
  const gate = new Promise<void>((resolve) => {
    releaseModule = resolve;
  });
  const requested = new Promise<void>((resolve) => {
    markRequested = resolve;
  });
  const delayModule = async (route: Route) => {
    markRequested();
    await gate;
    await route.continue();
  };
  await page.route(modulePattern, delayModule);
  try {
    await page.goto("", { waitUntil: "domcontentloaded" });
    await requested;
    const trigger = page.locator("button[data-open-modal]");
    await expect(trigger).toBeEnabled();
    await trigger.focus();
    await page.keyboard.press("Enter");
    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole("textbox")).toHaveCount(0);
    await run(releaseModule);
  } finally {
    releaseModule();
    await page.unroute(modulePattern, delayModule);
  }
}

test("keyboard opening focuses search when its delayed input appears", async ({
  page,
}) => {
  await withDelayedSearch(page, async (release) => {
    release();
    await expect(page.getByRole("dialog").getByRole("textbox")).toBeFocused();
  });
});

test("closing search before its input loads preserves trigger focus", async ({
  page,
}) => {
  await withDelayedSearch(page, async (release) => {
    await page.keyboard.press("Escape");
    const trigger = page.locator("button[data-open-modal]");
    await expect(page.getByRole("dialog")).not.toBeVisible();
    await expect(trigger).toBeFocused();
    release();
    await expect(page.locator("dialog input")).toHaveCount(1);
    await expect(trigger).toBeFocused();
  });
});

test("keyboard interaction before search loads does not steal focus", async ({
  page,
}) => {
  await withDelayedSearch(page, async (release) => {
    await page.keyboard.press("Tab");
    release();
    const input = page.getByRole("dialog").getByRole("textbox");
    await expect(input).toHaveCount(1);
    await expect(input).not.toBeFocused();
    await expect(page.getByRole("dialog")).toBeVisible();
  });
});

test("reopening search starts a new initial focus cycle", async ({ page }) => {
  await withDelayedSearch(page, async (release) => {
    await page.keyboard.press("Escape");
    const trigger = page.locator("button[data-open-modal]");
    await expect(trigger).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("dialog")).toBeVisible();
    release();
    await expect(page.getByRole("dialog").getByRole("textbox")).toBeFocused();
  });
});
