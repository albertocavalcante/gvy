// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { test, expect } from "./fixtures";
import type { Route } from "@playwright/test";

test("keyboard opening focuses search when its delayed input appears", async ({
  page,
}) => {
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
    releaseModule();
    await expect(dialog.getByRole("textbox")).toBeFocused();
  } finally {
    releaseModule();
    await page.unroute(modulePattern, delayModule);
  }
});
