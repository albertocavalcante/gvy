// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import {
  test as base,
  expect,
  type Page,
  type TestInfo,
} from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { isNavigationCancellation } from "./cancellation.mjs";

export const test = base.extend({
  page: async ({ page, baseURL, browser }, use, testInfo) => {
    testInfo.annotations.push({
      type: "browser-version",
      description: browser.version(),
    });
    const errors: string[] = [];
    const cancellations: {
      url: string;
      resourceType: string;
      errorText: string;
    }[] = [];
    const origin = new URL(baseURL!).origin;
    page.on("pageerror", (error) =>
      errors.push(`page error: ${error.message}`),
    );
    page.on("response", (response) => {
      if (
        new URL(response.url()).origin === origin &&
        response.request().resourceType() !== "document" &&
        response.status() >= 400
      ) {
        errors.push(`HTTP ${response.status()}: ${response.url()}`);
      }
    });
    page.on("requestfailed", (request) => {
      const errorText = request.failure()?.errorText;
      if (isNavigationCancellation(errorText)) {
        cancellations.push({
          url: request.url(),
          resourceType: request.resourceType(),
          errorText: errorText!,
        });
      } else if (
        new URL(request.url()).origin === origin &&
        request.resourceType() !== "document"
      ) {
        errors.push(`request failed: ${request.url()} ${errorText}`);
      }
    });
    await use(page);
    await testInfo.attach("canceled-requests", {
      body: JSON.stringify(cancellations, null, 2),
      contentType: "application/json",
    });
    expect(errors, "browser runtime and same-origin asset failures").toEqual(
      [],
    );
  },
});
export { expect };

export async function scanAccessibility(
  page: Page,
  testInfo: TestInfo,
  name: string,
) {
  const scan = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  await testInfo.attach(`axe-${name}`, {
    body: JSON.stringify(
      { violations: scan.violations, incomplete: scan.incomplete },
      null,
      2,
    ),
    contentType: "application/json",
  });
  expect(
    scan.violations.filter(
      (v) =>
        v.impact === "serious" ||
        v.impact === "critical" ||
        v.id === "label-content-name-mismatch",
    ),
    JSON.stringify(scan.violations, null, 2),
  ).toEqual([]);
}
