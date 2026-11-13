// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import type * as vscode from "vscode";
import type { TestSelectionDescriptor } from "./testTarget";

/** Per-controller discovery provenance. Runtime event items have no executable target. */
export class TestItemRegistry {
  private readonly metadata = new WeakMap<
    vscode.TestItem,
    TestSelectionDescriptor<vscode.TestItem>
  >();

  readonly describe = (
    item: vscode.TestItem,
  ): TestSelectionDescriptor<vscode.TestItem> =>
    this.metadata.get(item) ?? { kind: "unrepresentable" };

  registerSuite(
    item: vscode.TestItem,
    suite: string,
    completeChildren: readonly vscode.TestItem[] | undefined,
  ): void {
    this.metadata.set(item, {
      kind: "suite",
      suite,
      completeChildren:
        completeChildren === undefined ? undefined : [...completeChildren],
    });
  }

  registerFeature(item: vscode.TestItem, test: string): void {
    this.metadata.set(item, { kind: "feature", test });
  }
}
