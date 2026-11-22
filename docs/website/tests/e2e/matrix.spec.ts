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

test("home matches the reviewed Linux screenshot", async ({
  page,
}, testInfo) => {
  test.skip(
    process.platform !== "linux" || process.env.GVY_E2E_SYSTEM_CHROME === "1",
    "Visual baselines require bundled Chromium on Linux.",
  );
  await page.goto("");
  await page.evaluate(() => document.fonts.ready);
  await expect(page.locator("footer .meta time")).toBeVisible();
  const screenshot = await page.screenshot({
    fullPage: true,
    animations: "disabled",
    caret: "hide",
    scale: "css",
    style:
      "footer .meta time { display: inline-block; width: 8em; visibility: hidden; }",
  });
  // Retain candidate images even when no baseline exists yet.
  await testInfo.attach("home-visual-actual", {
    body: screenshot,
    contentType: "image/png",
  });
  expect(screenshot).toMatchSnapshot("home.png");
});
