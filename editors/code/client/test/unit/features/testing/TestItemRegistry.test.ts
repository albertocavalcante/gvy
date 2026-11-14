// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import type * as vscode from "vscode";
import { TestItemRegistry } from "../../../../src/features/testing/TestItemRegistry";

describe("TestItemRegistry", () => {
  it("isolates registrations and refuses items without discovery provenance", () => {
    const item = {} as vscode.TestItem;
    const first = new TestItemRegistry();
    const second = new TestItemRegistry();
    first.registerFeature(item, "literal feature.name");
    assert.deepStrictEqual(first.describe(item), {
      kind: "feature",
      test: "literal feature.name",
    });
    assert.deepStrictEqual(second.describe(item), { kind: "unrepresentable" });
  });

  it("copies a complete discovery snapshot independently of later child-array changes", () => {
    const suite = {} as vscode.TestItem;
    const feature = {} as vscode.TestItem;
    const children = [feature];
    const registry = new TestItemRegistry();
    registry.registerSuite(suite, "raw.Suite", children);
    children.length = 0;
    assert.deepStrictEqual(registry.describe(suite), {
      kind: "suite",
      suite: "raw.Suite",
      completeChildren: [feature],
    });
    registry.registerSuite(suite, "raw.Suite", undefined);
    assert.deepStrictEqual(registry.describe(suite), {
      kind: "suite",
      suite: "raw.Suite",
      completeChildren: undefined,
    });
  });
});
