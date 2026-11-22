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
  let requestSeen = false;
  let routeDone: Promise<void> | undefined;
  let failed = false;
  let primaryError: unknown;
  const gate = new Promise<void>((resolve) => {
    releaseModule = resolve;
  });
  const delayModule = (route: Route) => {
    requestSeen = true;
    routeDone = gate.then(() => route.continue());
    return routeDone;
  };
  // The test controls when the idle work starts and separately holds its network response.
  await page.addInitScript(() => {
    window.requestIdleCallback = (callback) =>
      window.setTimeout(
        () => callback({ didTimeout: false, timeRemaining: () => 50 }),
        0,
      );
    window.cancelIdleCallback = (id) => window.clearTimeout(id);
  });
  await page.route(modulePattern, delayModule);
  try {
    await page.goto("", { waitUntil: "domcontentloaded" });
    await expect
      .poll(() => requestSeen, {
        message: "Pagefind UI module request must reach the route",
        timeout: 10000,
      })
      .toBe(true);
    const trigger = page.locator("button[data-open-modal]");
    await expect(trigger).toBeEnabled();
    await trigger.focus();
    await page.keyboard.press("Enter");
    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole("textbox")).toHaveCount(0);
    await run(releaseModule);
  } catch (error) {
    failed = true;
    primaryError = error;
    throw error;
  } finally {
    releaseModule();
    const cleanupErrors: unknown[] = [];
    try {
      if (routeDone) await routeDone;
    } catch (error) {
      cleanupErrors.push(error);
    }
    try {
      if (!page.isClosed()) await page.unroute(modulePattern, delayModule);
    } catch (error) {
      cleanupErrors.push(error);
    }
    if (cleanupErrors.length) {
      throw new AggregateError(
        failed ? [primaryError, ...cleanupErrors] : cleanupErrors,
        "Delayed search route cleanup failed",
      );
    }
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
