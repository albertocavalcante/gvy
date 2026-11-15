// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { test, expect, scanAccessibility } from "./fixtures";

for (const route of [
  "",
  "getting-started/installation/",
  "reference/settings/",
]) {
  test(`${route || "home"} has no horizontal overflow or serious accessibility violations`, async ({
    page,
  }, testInfo) => {
    const response = await page.goto(route);
    expect(response?.status()).toBe(200);
    await page.evaluate(() => document.fonts.ready);
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth),
    ).toBeLessThanOrEqual(await page.evaluate(() => innerWidth));
    await scanAccessibility(page, testInfo, route || "home");
    if (!route)
      await testInfo.attach("home", {
        body: await page.screenshot({ fullPage: true }),
        contentType: "image/png",
      });
  });
}
