// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import {
  test as base,
  expect,
  type Page,
  type TestInfo,
  type Request,
} from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { cancellationDisposition, requestKey } from "./cancellation.mjs";

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
      purpose: string;
      sequence: number;
    }[] = [];
    const responseStatuses = new WeakMap<Request, number>();
    const finished = new Map<string, number>();
    let sequence = 0;
    const origin = new URL(baseURL!).origin;
    page.on("pageerror", (error) =>
      errors.push(`page error: ${error.message}`),
    );
    page.on("response", (response) => {
      responseStatuses.set(response.request(), response.status());
      if (
        new URL(response.url()).origin === origin &&
        response.request().resourceType() !== "document" &&
        response.status() >= 400
      ) {
        errors.push(`HTTP ${response.status()}: ${response.url()}`);
      }
    });
    page.on("requestfinished", (request) => {
      const finishedSequence = ++sequence;
      const status = responseStatuses.get(request);
      if (status !== undefined && status >= 200 && status < 400) {
        finished.set(
          requestKey(request.url(), request.resourceType()),
          finishedSequence,
        );
      }
    });
    page.on("requestfailed", (request) => {
      const failedSequence = ++sequence;
      const errorText = request.failure()?.errorText;
      const url = request.url();
      const resourceType = request.resourceType();
      const headers = request.headers();
      const purpose = [headers["sec-purpose"], headers.purpose]
        .filter(Boolean)
        .join("; ");
      const record = {
        url,
        resourceType,
        errorText,
        purpose,
        sequence: failedSequence,
      };
      const disposition = cancellationDisposition(record, finished);
      if (disposition !== "other") {
        cancellations.push({ ...record, errorText: errorText! });
      } else if (
        new URL(url).origin === origin &&
        resourceType !== "document"
      ) {
        errors.push(`request failed: ${url} ${errorText}`);
      }
    });
    await use(page);
    const classified = cancellations.map((record) => {
      const disposition = cancellationDisposition(record, finished);
      const finishedSequence = finished.get(
        requestKey(record.url, record.resourceType),
      );
      if (
        disposition === "unrecovered" &&
        new URL(record.url).origin === origin &&
        record.resourceType !== "document"
      ) {
        errors.push(
          `request failed: ${record.url} ${record.errorText} (unrecovered)`,
        );
      }
      return { ...record, disposition, finishedSequence };
    });
    await testInfo.attach("canceled-requests", {
      body: JSON.stringify(classified, null, 2),
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
