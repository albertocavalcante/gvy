// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import {
  resolveTestTarget,
  selectRunItems,
  TestItemLike,
} from "../../../../src/features/testing/testTarget";

function item(id: string, parent?: TestItemLike): TestItemLike {
  return { id, parent };
}

describe("testTarget", () => {
  describe("resolveTestTarget", () => {
    it("treats a top-level item in a package as a suite", () => {
      const suite = item("com.example.MySpec");
      assert.deepStrictEqual(resolveTestTarget(suite), {
        suite: "com.example.MySpec",
      });
    });

    it("splits a method on its parent id, not on the last dot", () => {
      const suite = item("com.x.Spec");
      const feature = item("com.x.Spec.parses 1.5 values", suite);
      assert.deepStrictEqual(resolveTestTarget(feature), {
        suite: "com.x.Spec",
        test: "parses 1.5 values",
      });
    });

    it("runs the enclosing feature for a dynamic iteration item", () => {
      const suite = item("com.x.Spec");
      const feature = item("com.x.Spec.adds", suite);
      const iteration = item("com.x.Spec.adds.adds [a: 1, #0]", feature);
      assert.deepStrictEqual(resolveTestTarget(iteration), {
        suite: "com.x.Spec",
        test: "adds",
      });
    });
  });

  describe("selectRunItems", () => {
    const a = item("A");
    const b = item("B");
    const roots = {
      forEach: (cb: (i: TestItemLike) => void) => [a, b].forEach(cb),
    };

    it("runs every root item when include is undefined (Run All)", () => {
      assert.deepStrictEqual(selectRunItems(undefined, undefined, roots), [
        a,
        b,
      ]);
    });

    it("keeps an explicit include list", () => {
      assert.deepStrictEqual(selectRunItems([b], undefined, roots), [b]);
    });

    it("drops excluded items", () => {
      assert.deepStrictEqual(selectRunItems(undefined, [a], roots), [b]);
    });
  });
});
