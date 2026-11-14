// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { test, expect } from "./fixtures";
import AxeBuilder from "@axe-core/playwright";

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
    const scan = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
      .analyze();
    expect(
      scan.violations.filter(
        (v) =>
          v.impact === "serious" ||
          v.impact === "critical" ||
          v.id === "label-content-name-mismatch",
      ),
      JSON.stringify(scan.violations, null, 2),
    ).toEqual([]);
    if (!route)
      await testInfo.attach("home", {
        body: await page.screenshot({ fullPage: true }),
        contentType: "image/png",
      });
  });
}
