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
    // A modal must keep the underlying page inert, even to programmatic focus.
    await page
      .locator("a.site-title")
      .first()
      .evaluate((link: HTMLElement) => link.focus());
    await expect(input).toBeFocused();
    await scanAccessibility(page, testInfo, "search-dialog-open");
    await page.evaluate(() => {
      const events: {
        type: string;
        target: string;
        windowTarget: boolean;
        inDialog: boolean;
      }[] = [];
      Object.assign(window, { focusEvents: events });
      for (const type of ["focusin", "focusout", "focus", "blur"]) {
        window.addEventListener(
          type,
          (event) => {
            const target = event.target;
            events.push({
              type,
              windowTarget: target === window,
              inDialog:
                target instanceof Node &&
                !!document.querySelector("dialog[open]")?.contains(target),
              target:
                target instanceof Element
                  ? target.outerHTML.slice(0, 500)
                  : String(target),
            });
          },
          true,
        );
      }
    });
    const states = [];
    const keys = [
      "Tab",
      "Tab",
      "Tab",
      "Tab",
      "Tab",
      "Shift+Tab",
      "Shift+Tab",
      "Shift+Tab",
      "Shift+Tab",
      "Shift+Tab",
    ];
    const originalKeyCount = keys.length;
    for (let i = 0; i < keys.length; i++) {
      const key = keys[i];
      const eventOffset = await page.evaluate(
        () => Reflect.get(window, "focusEvents").length,
      );
      await page.keyboard.press(key);
      const state = await dialog.evaluate(
        (el, offset) => ({
          events: Reflect.get(window, "focusEvents").slice(offset) as {
            type: string;
            windowTarget: boolean;
            inDialog: boolean;
          }[],
          active: document.activeElement?.outerHTML.slice(0, 500),
          hasFocus: document.hasFocus(),
          inDialog: el.contains(document.activeElement),
          bodyActive: document.activeElement === document.body,
        }),
        eventOffset,
      );
      states.push({ key, ...state });
      // If the last press left focus in browser chrome, measure the return too.
      if (i === originalKeyCount - 1 && state.bodyActive) keys.push(key);
    }
    const events = await page.evaluate(() =>
      Reflect.get(window, "focusEvents"),
    );
    await testInfo.attach("search-focus-observations", {
      body: JSON.stringify({ states, events }, null, 2),
      contentType: "application/json",
    });
    for (let i = 0; i < states.length; i++) {
      const state = states[i];
      expect(
        state.events.some(
          (event) => event.type === "focusin" && !event.inDialog,
        ),
        JSON.stringify(state),
      ).toBe(false);
      if (state.inDialog) continue;
      const windowBlurred = state.events.some(
        (event) => event.type === "blur" && event.windowTarget,
      );
      expect(
        state.bodyActive && (!state.hasFocus || windowBlurred),
        JSON.stringify(state),
      ).toBe(true);
      expect(
        states[i + 1]?.inDialog,
        `Focus must return to the dialog after ${JSON.stringify(state)}`,
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
