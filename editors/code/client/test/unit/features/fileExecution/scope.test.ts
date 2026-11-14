// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import { scopeChanged } from "../../../../src/features/fileExecution/scope";

describe("build scope revalidation", () => {
  it("detects documents opened and dirtied after the build started", () => {
    const original = {
      uri: { scheme: "file", fsPath: "/work/a" },
      version: 1,
      isDirty: false,
    };
    const newlyOpened = {
      uri: { scheme: "file", fsPath: "/work/build.gradle" },
      version: 2,
      isDirty: true,
    };
    assert.strictEqual(
      scopeChanged("/work", new Map([[original, 1]]), [original, newlyOpened]),
      true,
    );
    assert.strictEqual(
      scopeChanged("/elsewhere", new Map(), [newlyOpened]),
      false,
    );
  });
});
