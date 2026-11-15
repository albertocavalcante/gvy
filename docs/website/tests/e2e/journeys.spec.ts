// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { test, expect, scanAccessibility } from "./fixtures";

test("search results lead to their destinations", async ({ page, baseURL }) => {
  for (const [query, target, heading] of [
    ["installation", "getting-started/installation/", "Installation"],
    ["Java", "guides/java/", "Java"],
    ["Jenkins", "guides/jenkins/", "Jenkins"],
    ["Spock", "guides/spock/", "Spock"],
    ["troubleshooting", "guides/troubleshooting/", "Troubleshooting"],
  ]) {
    await page.goto("");
    await page.locator("button[data-open-modal]").click();
    const dialog = page.getByRole("dialog");
    await dialog.getByRole("textbox").fill(query);
    await dialog.locator(`a[href*="/${target}"]`).first().click();
    await expect(page).toHaveURL(
      new RegExp(`${new URL(target, baseURL).pathname}(?:#.*)?$`),
    );
    await expect(page.locator("h1").first()).toContainText(heading, {
      ignoreCase: true,
    });
  }
});

test("installation leads to the first project guide", async ({ page }) => {
  await page.goto("getting-started/installation/");
  await page
    .getByRole("link", { name: "Quickstart", exact: true })
    .first()
    .click();
  await expect(page).toHaveURL(/\/guides\/quickstart\/$/);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "quickstart",
  );
  await expect(
    page.getByText("Hello.groovy", { exact: false }).first(),
  ).toBeVisible();
});

test("mobile navigation opens by keyboard and closes with Escape", async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 360, height: 900 });
  await page.goto("getting-started/installation/");
  const trigger = page.locator('button[popovertarget="starlight__sidebar"]');
  const menu = page.locator("#starlight__sidebar");
  await trigger.focus();
  await page.keyboard.press("Enter");
  await expect(menu).toHaveJSProperty("popover", "auto");
  await expect(
    menu.locator('a[href*="/guides/quickstart/"]').first(),
  ).toBeVisible();
  await scanAccessibility(page, testInfo, "mobile-navigation-open");
  await page.keyboard.press("Escape");
  await expect(
    menu.locator('a[href*="/guides/quickstart/"]').first(),
  ).not.toBeVisible();
  await expect(trigger).toBeFocused();
});

for (const width of [360, 1440])
  test(`search dialog supports keyboard focus and closing at ${width}px`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("");
    const trigger = page.locator("button[data-open-modal]");
    await trigger.focus();
    await page.keyboard.press("Enter");
    const dialog = page.getByRole("dialog");
    const input = dialog.getByRole("textbox");
    await expect(dialog).toBeVisible();
    await expect(input).toBeFocused();
    await scanAccessibility(page, testInfo, "search-dialog-open");
    for (let i = 0; i < 5; i++) {
      await page.keyboard.press("Tab");
      expect(
        await dialog.evaluate(
          (el) =>
            (document.activeElement === document.body &&
              !document.hasFocus()) ||
            el.contains(document.activeElement),
        ),
      ).toBe(true);
    }
    await page.keyboard.press("Escape");
    await expect(dialog).not.toBeVisible();
    await expect(trigger).toBeFocused();
  });

test("theme selection persists across navigation and reload", async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("");
  const theme = page.locator("starlight-theme-select select").first();
  await theme.selectOption("light");
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await theme.selectOption("dark");
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.goto("getting-started/installation/");
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await expect(
    page.locator("starlight-theme-select select").first(),
  ).toHaveValue("dark");
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await expect(
    page.locator("starlight-theme-select select").first(),
  ).toHaveValue("dark");
});
