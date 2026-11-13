// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import {
  planRunItems,
  TestItemLike,
  TestSelectionDescriptor,
  TestSelectionPlan,
} from "../../../../src/features/testing/testTarget";

interface SelectionItem extends TestItemLike {
  readonly parent?: SelectionItem;
  readonly uri: string;
}

describe("planRunItems", () => {
  let descriptors: Map<SelectionItem, TestSelectionDescriptor<SelectionItem>>;
  let first: SelectionItem;
  let second: SelectionItem;
  let a: SelectionItem;
  let b: SelectionItem;
  let c: SelectionItem;
  let row: SelectionItem;
  let directRow: SelectionItem;

  function suite(id: string, uri: string): SelectionItem {
    const value = { id, uri };
    descriptors.set(value, {
      kind: "suite",
      suite: id,
      completeChildren: undefined,
    });
    return value;
  }

  function feature(parent: SelectionItem, name: string): SelectionItem {
    const value = { id: `${parent.id}.${name}`, parent, uri: parent.uri };
    descriptors.set(value, { kind: "feature", test: name });
    return value;
  }

  function iteration(parent: SelectionItem): SelectionItem {
    const value = { id: `${parent.id}.row`, parent, uri: parent.uri };
    descriptors.set(value, { kind: "unrepresentable" });
    return value;
  }

  function complete(parent: SelectionItem, ...children: SelectionItem[]) {
    const current = descriptors.get(parent);
    assert.ok(current?.kind === "suite");
    descriptors.set(parent, { ...current, completeChildren: children });
  }

  function request(
    include: readonly SelectionItem[] | undefined,
    exclude?: readonly SelectionItem[],
    roots: readonly SelectionItem[] = [first, second],
  ) {
    return planRunItems(
      include,
      exclude,
      { forEach: (callback) => roots.forEach(callback) },
      (value) => descriptors.get(value) ?? { kind: "unrepresentable" },
    );
  }

  function ready(result: TestSelectionPlan<SelectionItem>) {
    assert.ok(result.kind === "ready", "Expected an executable complete plan");
    return result.selections;
  }

  function refusals(result: TestSelectionPlan<SelectionItem>) {
    assert.ok(result.kind === "ready", "Expected an executable plan");
    return result.refused;
  }

  function unsupported(
    result: TestSelectionPlan<SelectionItem>,
    reason: "incomplete-suite" | "unrepresentable-selection",
  ) {
    assert.ok(result.kind === "unsupported", "Expected a controlled refusal");
    assert.strictEqual(result.reason, reason);
    assert.strictEqual("selections" in result, false);
    assert.strictEqual("targets" in result, false);
  }

  beforeEach(() => {
    descriptors = new Map();
    first = suite("example.Spec", "file:///first/Spec.groovy");
    second = suite("other.Spec", "file:///second/Spec.groovy");
    a = feature(first, "a");
    b = feature(first, "parses 1.5 values");
    c = feature(second, "c");
    row = iteration(a);
    // The real Gradle producer can attach runtime rows directly to their suite.
    directRow = iteration(first);
    complete(first, a, b);
    complete(second, c);
  });

  it("binds Run All to the original whole-suite items", () => {
    assert.deepStrictEqual(ready(request(undefined)), [
      { item: first, target: { suite: first.id } },
      { item: second, target: { suite: second.id } },
    ]);
  });

  it("binds discovered server names independently of opaque UI identifiers", () => {
    const opaqueSuite = { id: "ui:root", uri: first.uri };
    const opaqueFeature = {
      id: "ui:feature",
      parent: opaqueSuite,
      uri: first.uri,
    };
    const suiteNames = {
      suite: "example.Spec",
      completeChildren: [opaqueFeature],
    };
    const featureNames = { test: "parses 1.5 values" };
    descriptors.set(opaqueSuite, { ...suiteNames, kind: "suite" });
    descriptors.set(opaqueFeature, { ...featureNames, kind: "feature" });
    assert.deepStrictEqual(
      ready(request([opaqueFeature], undefined, [opaqueSuite])),
      [
        {
          item: opaqueFeature,
          target: { suite: "example.Spec", test: "parses 1.5 values" },
        },
      ],
    );
  });

  it("preserves an explicit empty include", () => {
    assert.deepStrictEqual(ready(request([])), []);
  });

  for (const runAll of [true, false]) {
    it(`expands a complete ${runAll ? "Run All" : "included suite"} around an excluded feature`, () => {
      assert.deepStrictEqual(
        ready(request(runAll ? undefined : [first], [a], [first])),
        [{ item: b, target: { suite: first.id, test: "parses 1.5 values" } }],
      );
    });
  }

  it("prunes included descendants of an excluded suite", () => {
    assert.deepStrictEqual(ready(request([a, row], [first])), []);
  });

  it("drops an excluded complete subtree without affecting another suite", () => {
    assert.deepStrictEqual(ready(request(undefined, [first])), [
      { item: second, target: { suite: second.id } },
    ]);
  });

  it("deduplicates repeated object includes", () => {
    assert.deepStrictEqual(ready(request([a, a])), [
      { item: a, target: { suite: first.id, test: "a" } },
    ]);
  });

  it("coalesces a feature and its later included suite", () => {
    assert.deepStrictEqual(ready(request([a, first])), [
      { item: first, target: { suite: first.id } },
    ]);
  });

  for (const covering of ["suite", "feature"] as const) {
    it(`accepts a row covered by an explicit whole ${covering}`, () => {
      const parent = covering === "suite" ? first : a;
      assert.deepStrictEqual(ready(request([row, parent])), [
        {
          item: parent,
          target:
            covering === "suite"
              ? { suite: first.id }
              : { suite: first.id, test: "a" },
        },
      ]);
    });
  }

  it("prunes a row exclusion already covered by its excluded feature", () => {
    assert.deepStrictEqual(ready(request([first], [row, a])), [
      { item: b, target: { suite: first.id, test: "parses 1.5 values" } },
    ]);
  });

  it("allows a whole incomplete suite or an explicit known fallback feature", () => {
    descriptors.set(first, {
      kind: "suite",
      suite: first.id,
      completeChildren: undefined,
    });
    assert.deepStrictEqual(ready(request([first])), [
      { item: first, target: { suite: first.id } },
    ]);
    assert.deepStrictEqual(ready(request([a])), [
      { item: a, target: { suite: first.id, test: "a" } },
    ]);
  });

  it("refuses expansion of an incomplete suite, including a partial fallback", () => {
    descriptors.set(first, {
      kind: "suite",
      suite: first.id,
      completeChildren: undefined,
    });
    unsupported(request([first], [a]), "incomplete-suite");
  });

  it("distinguishes an authoritative empty snapshot from missing completeness", () => {
    complete(first);
    assert.deepStrictEqual(ready(request([first])), [
      { item: first, target: { suite: first.id } },
    ]);
    // A child claiming that parent contradicts the supplied complete snapshot.
    unsupported(request([first], [a]), "unrepresentable-selection");
  });

  for (const form of ["nested", "direct", "multiple"] as const) {
    it(`skips and reports an uncovered ${form} runtime row selection`, () => {
      const include =
        form === "direct"
          ? [directRow]
          : form === "multiple"
            ? [row, iteration(a)]
            : [row];
      const result = request(include);
      assert.deepStrictEqual(ready(result), []);
      assert.deepStrictEqual(
        refusals(result),
        include.map((item) => ({ item, reason: "unrunnable-iteration" })),
      );
    });
  }

  it("runs the runnable part of a request that also contains a runtime row", () => {
    const result = request([a, directRow, c, row]);
    assert.deepStrictEqual(ready(result), [
      { item: a, target: { suite: first.id, test: "a" } },
      { item: c, target: { suite: second.id, test: "c" } },
    ]);
    assert.deepStrictEqual(
      refusals(result).map(({ item }) => item),
      [directRow],
    );
  });

  for (const selected of ["suite", "feature"] as const) {
    it(`refuses only the ${selected} an effective row exclusion cuts`, () => {
      const item = selected === "suite" ? first : a;
      const result = request([item, c], [row]);
      assert.deepStrictEqual(ready(result), [
        { item: c, target: { suite: second.id, test: "c" } },
      ]);
      assert.deepStrictEqual(refusals(result), [
        { item, reason: "excluded-iteration" },
      ]);
    });
  }

  it("runs the other suites of Run All when one iteration row is excluded", () => {
    const result = request(undefined, [row]);
    assert.deepStrictEqual(ready(result), [
      { item: second, target: { suite: second.id } },
    ]);
    assert.deepStrictEqual(refusals(result), [
      { item: first, reason: "excluded-iteration" },
    ]);
  });

  for (const selected of ["suite", "feature"] as const) {
    it(`refuses a ${selected} an unowned direct-suite row exclusion may cut`, () => {
      const item = selected === "suite" ? first : a;
      const result = request([item, second], [directRow]);
      assert.deepStrictEqual(ready(result), [
        { item: second, target: { suite: second.id } },
      ]);
      assert.deepStrictEqual(refusals(result), [
        { item, reason: "excluded-iteration" },
      ]);
    });
  }

  it("does not confuse a proven different feature's excluded row", () => {
    assert.deepStrictEqual(ready(request([b], [row])), [
      { item: b, target: { suite: first.id, test: "parses 1.5 values" } },
    ]);
  });

  it("does not confuse an unrelated suite's excluded runtime row", () => {
    assert.deepStrictEqual(ready(request([c], [directRow])), [
      { item: c, target: { suite: second.id, test: "c" } },
    ]);
  });

  it("prunes an explicitly excluded row before checking representability", () => {
    assert.deepStrictEqual(ready(request([row], [row])), []);
  });

  it("runs the rest of a request and reports a later runtime row", () => {
    const result = request([second, row]);
    assert.deepStrictEqual(ready(result), [
      { item: second, target: { suite: second.id } },
    ]);
    assert.deepStrictEqual(refusals(result), [
      { item: row, reason: "unrunnable-iteration" },
    ]);
  });

  it("returns no executable plan when a later unit is structurally unsupported", () => {
    const stale = suite("stale.Spec", "file:///stale/Spec.groovy");
    unsupported(request([second, stale]), "unrepresentable-selection");
  });

  it("rejects a foreign-parent feature in a supplied complete snapshot", () => {
    complete(first, a, c);
    unsupported(request([first], [a]), "unrepresentable-selection");
  });

  it("rejects exclusions contradicting a supplied complete snapshot", () => {
    complete(first, b);
    unsupported(request([first], [a]), "unrepresentable-selection");
  });

  it("rejects runtime rows accidentally included in the discovery snapshot", () => {
    complete(first, a, directRow);
    unsupported(request([first], [a]), "unrepresentable-selection");
  });

  it("preserves distinct folder identities even with identical target IDs", () => {
    const other = suite(first.id, "file:///third/Spec.groovy");
    const sameName = feature(other, "a");
    assert.deepStrictEqual(
      ready(request([a, sameName], undefined, [first, other])),
      [
        { item: a, target: { suite: first.id, test: "a" } },
        { item: sameName, target: { suite: first.id, test: "a" } },
      ],
    );
  });

  it("rejects stale item trees instead of matching another root by ID", () => {
    const replacement = suite(first.id, "file:///replacement/Spec.groovy");
    unsupported(
      request([a], undefined, [replacement]),
      "unrepresentable-selection",
    );
  });

  it("rejects a row mislabeled as a feature rather than widening its target", () => {
    descriptors.set(row, { kind: "feature", test: "row" });
    unsupported(request([row]), "unrepresentable-selection");
  });

  it("rejects a selected feature missing from the snapshot a split relies on", () => {
    complete(first, b);
    unsupported(request([a], [b]), "unrepresentable-selection");
  });

  it("does not consult the discovery snapshot when nothing is excluded", () => {
    // Neither a stale snapshot nor overloaded names can matter to a plain run.
    complete(first, c);
    assert.deepStrictEqual(ready(request([first])), [
      { item: first, target: { suite: first.id } },
    ]);
    assert.deepStrictEqual(ready(request([a])), [
      { item: a, target: { suite: first.id, test: "a" } },
    ]);
  });

  it("still validates the snapshot of a suite that must be split", () => {
    complete(first, c);
    unsupported(request([first], [a]), "unrepresentable-selection");
  });

  describe("overloaded (same-named) features", () => {
    let overload: SelectionItem;

    beforeEach(() => {
      // Two JUnit 5 overloads are both discovered under the name "a".
      overload = feature(first, "a");
      complete(first, a, overload, b);
    });

    it("runs Run All as whole classes without refusing the workspace", () => {
      assert.deepStrictEqual(ready(request(undefined)), [
        { item: first, target: { suite: first.id } },
        { item: second, target: { suite: second.id } },
      ]);
    });

    it("runs the class itself as one target", () => {
      assert.deepStrictEqual(ready(request([first])), [
        { item: first, target: { suite: first.id } },
      ]);
    });

    it("splits around an unrelated exclusion with one target for both overloads", () => {
      assert.deepStrictEqual(ready(request([first], [b])), [
        { item: a, target: { suite: first.id, test: "a" } },
      ]);
    });

    it("refuses only the class when an exclusion cuts one overload", () => {
      const result = request(undefined, [overload]);
      assert.deepStrictEqual(ready(result), [
        { item: second, target: { suite: second.id } },
      ]);
      assert.deepStrictEqual(refusals(result), [
        { item: first, reason: "ambiguous-feature-name" },
      ]);
    });

    it("refuses only a selected feature whose overload is excluded", () => {
      const result = request([a, c], [overload]);
      assert.deepStrictEqual(ready(result), [
        { item: c, target: { suite: second.id, test: "c" } },
      ]);
      assert.deepStrictEqual(refusals(result), [
        { item: a, reason: "ambiguous-feature-name" },
      ]);
    });

    it("drops a class whose overloads are all excluded", () => {
      assert.deepStrictEqual(ready(request([first], [a, overload, b])), []);
    });

    it("runs both selected overloads with one command beside other tests", () => {
      // The shape of Rerun Failed Tests after both overloads failed.
      const result = request([a, overload, c]);
      assert.deepStrictEqual(ready(result), [
        { item: a, target: { suite: first.id, test: "a" } },
        { item: c, target: { suite: second.id, test: "c" } },
      ]);
      assert.deepStrictEqual(refusals(result), []);
    });

    it("refuses both selected overloads when a third same-named one is excluded", () => {
      const third = feature(first, "a");
      complete(first, a, overload, third, b);
      const result = request([a, overload, c], [third]);
      assert.deepStrictEqual(ready(result), [
        { item: c, target: { suite: second.id, test: "c" } },
      ]);
      assert.deepStrictEqual(refusals(result), [
        { item: a, reason: "ambiguous-feature-name" },
        { item: overload, reason: "ambiguous-feature-name" },
      ]);
    });
  });

  it("deduplicates a repeated feature object in the supplied snapshot", () => {
    complete(first, a, b, b);
    assert.deepStrictEqual(ready(request([first], [a])), [
      { item: b, target: { suite: first.id, test: "parses 1.5 values" } },
    ]);
  });

  it("coalesces distinct snapshot items resolving to the same command target", () => {
    const sameTarget = { id: "a", parent: first, uri: first.uri };
    descriptors.set(sameTarget, { kind: "feature", test: "a" });
    complete(first, a, sameTarget, b);
    assert.deepStrictEqual(ready(request([first], [b])), [
      { item: a, target: { suite: first.id, test: "a" } },
    ]);
  });

  it("issues one command for selected same-named fallback features", () => {
    descriptors.set(first, {
      kind: "suite",
      suite: first.id,
      completeChildren: undefined,
    });
    const sameTarget = feature(first, "a");
    const result = request([a, sameTarget, c]);
    assert.deepStrictEqual(ready(result), [
      { item: a, target: { suite: first.id, test: "a" } },
      { item: c, target: { suite: second.id, test: "c" } },
    ]);
    assert.deepStrictEqual(refusals(result), []);
  });

  it("refuses a fallback command matching a distinct excluded feature target", () => {
    descriptors.set(first, {
      kind: "suite",
      suite: first.id,
      completeChildren: undefined,
    });
    const sameTarget = feature(first, "a");
    const result = request([a], [sameTarget]);
    assert.deepStrictEqual(ready(result), []);
    assert.deepStrictEqual(refusals(result), [
      { item: a, reason: "ambiguous-feature-name" },
    ]);
  });

  it("keeps an explicit whole suite when it covers a direct-suite runtime row", () => {
    assert.deepStrictEqual(ready(request([directRow, first])), [
      { item: first, target: { suite: first.id } },
    ]);
  });

  it("refuses a stale excluded item rather than matching it by ID", () => {
    const oldSuite = suite(first.id, first.uri);
    const oldFeature = feature(oldSuite, "a");
    unsupported(request([a], [oldFeature]), "unrepresentable-selection");
  });

  it("refuses Run All with an exclusion from a replaced tree", () => {
    const oldSuite = suite(first.id, first.uri);
    unsupported(request(undefined, [oldSuite]), "unrepresentable-selection");
  });

  it("preserves an empty request even with a stale exclusion", () => {
    const oldSuite = suite(first.id, first.uri);
    assert.deepStrictEqual(ready(request([], [oldSuite])), []);
  });

  it("ignores an excluded feature from another known current root", () => {
    assert.deepStrictEqual(ready(request([a], [c])), [
      { item: a, target: { suite: first.id, test: "a" } },
    ]);
  });

  it("refuses cyclic parent chains without traversing indefinitely", () => {
    const cycle: SelectionItem & { parent?: SelectionItem } = {
      id: "cycle",
      uri: first.uri,
    };
    cycle.parent = cycle;
    unsupported(request([cycle]), "unrepresentable-selection");
  });

  it("does not rescan every excluded feature for every selected feature", () => {
    const count = 512;
    const children = Array.from({ length: count }, (_, index) =>
      feature(first, `feature ${index}`),
    );
    let nameReads = 0;
    children.forEach((child, index) => {
      descriptors.set(child, {
        kind: "feature",
        get test() {
          nameReads++;
          return `feature ${index}`;
        },
      });
    });
    descriptors.set(first, {
      kind: "suite",
      suite: first.id,
      completeChildren: children,
    });
    const included = children.filter((_, index) => index % 2 === 0);
    const excluded = children.filter((_, index) => index % 2 !== 0);
    const result = ready(request(included, excluded));
    assert.deepStrictEqual(
      result.map(({ item }) => item),
      included,
    );
    // A deterministic input-access budget catches quadratic rescanning without
    // a machine-dependent wall-clock assertion. Leave room for linear passes.
    assert.ok(
      nameReads <= count * 8,
      `Repeated feature-name reads: ${nameReads}`,
    );
  });
});
