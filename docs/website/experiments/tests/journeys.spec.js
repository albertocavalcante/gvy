// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

test('selected routes, fragments, edit links and actual 404', async ({ page }) => {
  await page.goto('./');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Groovy language tools');
  await page.getByRole('navigation').getByRole('link', { name: 'Quickstart' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Quickstart');
  await expect(page.getByRole('link', { name: 'Edit this page' })).toHaveAttribute('href', /docs\/user\/quickstart\.md$/);
  await page.getByRole('navigation').getByRole('link', { name: 'Settings' }).click();
  await expect(page.locator('#stable-v054')).toHaveCount(1);
  await expect(page.getByRole('link', { name: 'Edit this page' })).toHaveAttribute('href', /editors\/code\/package\.json$/);
  const response = await page.goto('missing/');
  expect(response.status()).toBe(404);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Page not found');
});

test('search finds the selected corpus', async ({ page }) => {
  for (const [query, route, heading] of [
    ['Greeter', 'guides/quickstart/', 'Quickstart'],
    ['incrementalThreshold', 'reference/settings/', 'VS Code settings'],
  ]) {
    await page.goto('./');
    await page.getByLabel('Search').fill(query);
    await page.getByRole('button', { name: 'Search' }).click();
    const result = page.locator('#results a').first();
    await expect(result).toHaveAttribute('href', new RegExp(route));
    await result.click();
    await expect(page).toHaveURL(new RegExp(`/gvy/${route}$`));
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(heading);
  }
});

test('essential content survives without JavaScript', async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false });
  const page = await context.newPage();
  await page.goto('http://127.0.0.1:4321/gvy/');
  await expect(page.locator('#about-this-experiment')).toHaveText('About this experiment');
  await page.goto('http://127.0.0.1:4321/gvy/guides/quickstart/');
  await expect(page.getByRole('heading', { name: 'Quickstart' })).toBeVisible();
  await expect(page.locator('pre')).toContainText('Greeter');
  await page.goto('http://127.0.0.1:4321/gvy/reference/settings/');
  await expect(page.locator('table').first()).toBeVisible();
  await context.close();
});

test('navigation works by keyboard and follows system theme', async ({ page }) => {
  for (const scheme of ['light', 'dark']) {
    await page.emulateMedia({ colorScheme: scheme });
    await page.goto('./');
    await page.keyboard.press('Tab');
    await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused();
    await expect(page.locator('html')).toHaveCSS('color', scheme === 'light' ? 'rgb(23, 32, 42)' : 'rgb(241, 243, 245)');
    await expect(page.locator('html')).toHaveCSS('background-color', scheme === 'light' ? 'rgb(255, 255, 255)' : 'rgb(22, 27, 34)');
    await page.keyboard.press('Tab');
    await expect(page.getByRole('navigation').getByRole('link', { name: 'Home' })).toBeFocused();
  }
});


test('all selected local links and fragments resolve', async ({ page, request }) => {
  const failures = [];
  page.on('response', response => {
    if (response.url().startsWith('http://127.0.0.1:4321/gvy/') && response.status() >= 400) failures.push(`${response.status()} ${response.url()}`);
  });
  for (const route of ['./', 'guides/quickstart/', 'reference/settings/']) {
    await page.goto(route);
    const hrefs = await page.locator('a[href]').evaluateAll(links => links.map(link => link.href));
    for (const href of new Set(hrefs)) {
      const url = new URL(href);
      if (url.origin !== 'http://127.0.0.1:4321') continue;
      expect(url.pathname.startsWith('/gvy/'), href).toBeTruthy();
      const response = await request.get(url.origin + url.pathname);
      expect(response.status(), href).toBe(200);
      if (url.hash) {
        const html = await response.text();
        const exists = await page.evaluate(({ html, id }) => !!new DOMParser().parseFromString(html, 'text/html').getElementById(id), { html, id: decodeURIComponent(url.hash.slice(1)) });
        expect(exists, href).toBeTruthy();
      }
    }
  }
  await page.goto('reference/settings/');
  await expect(page.getByRole('link', { name: 'stable manifest' })).toHaveAttribute('href', 'https://github.com/albertocavalcante/gvy/blob/main/docs/website/stable-v0.5.4-package.json');
  await expect(page.getByRole('link', { name: 'current manifest' })).toHaveAttribute('href', 'https://github.com/albertocavalcante/gvy/blob/main/editors/code/package.json');
  expect(failures).toEqual([]);
});

test('overflowing code and tables scroll by keyboard without JavaScript', async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 240, height: 600 } });
  const page = await context.newPage();
  for (const [route, selector] of [['guides/quickstart/', 'pre'], ['reference/settings/', 'table']]) {
    await page.goto(`http://127.0.0.1:4321/gvy/${route}`);
    const region = page.locator(selector).first();
    await expect(region).toHaveAttribute('tabindex', '0');
    expect(await region.evaluate(el => el.scrollWidth > el.clientWidth)).toBeTruthy();
    let focused = false;
    for (let i = 0; i < 100; i++) {
      await page.keyboard.press('Tab');
      if (await region.evaluate(el => document.activeElement === el)) { focused = true; break; }
    }
    expect(focused).toBeTruthy();
    await page.keyboard.press('ArrowRight');
    await expect.poll(() => region.evaluate(el => el.scrollLeft)).toBeGreaterThan(0);
  }
  await context.close();
});

test('representative pages have no serious or critical axe findings', async ({ page }, testInfo) => {
  const blocking = [];
  for (const route of ['./', 'guides/quickstart/', 'reference/settings/']) {
    await page.goto(route);
    const results = await new AxeBuilder({ page }).analyze();
    await testInfo.attach(`axe-${route.replaceAll('/', '-') || 'home'}`, {
      body: JSON.stringify({ violations: results.violations, incomplete: results.incomplete }, null, 2),
      contentType: 'application/json',
    });
    blocking.push(...results.violations.filter(item => ['serious', 'critical'].includes(item.impact) || item.id === 'region').map(item => ({ route, id: item.id, impact: item.impact })));
  }
  expect(blocking).toEqual([]);
});
