// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { test as base, expect } from "@playwright/test";

export const test = base.extend({
  page: async ({ page, baseURL, browser }, use, testInfo) => {
    testInfo.annotations.push({
      type: "browser-version",
      description: browser.version(),
    });
    const errors: string[] = [];
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
      if (
        new URL(request.url()).origin === origin &&
        request.resourceType() !== "document" &&
        !request.failure()?.errorText.includes("ERR_ABORTED")
      ) {
        errors.push(
          `request failed: ${request.url()} ${request.failure()?.errorText}`,
        );
      }
    });
    await use(page);
    expect(errors, "browser runtime and same-origin asset failures").toEqual(
      [],
    );
  },
});
export { expect };
