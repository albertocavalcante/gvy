// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Pure helpers that map Test Explorer items to what the language server's
 * `groovy/runTest` request expects. Kept free of the vscode API so they can be
 * unit-tested directly.
 */

/** Minimal structural view of a vscode.TestItem. */
export interface TestItemLike {
  readonly id: string;
  readonly parent?: TestItemLike;
}

export interface TestTarget {
  suite: string;
  test?: string;
}

interface ItemCollection<T> {
  forEach(callback: (item: T) => unknown): void;
}

/** Discovery provenance, rather than the current (possibly dynamic) children. */
export type TestSelectionDescriptor<T> =
  | {
      readonly kind: "suite";
      readonly suite: string;
      readonly completeChildren: readonly T[] | undefined;
    }
  | { readonly kind: "feature"; readonly test: string }
  | { readonly kind: "unrepresentable" };

/** A selected item that cannot be run exactly; the rest of the plan still can. */
export interface RefusedSelection<T> {
  readonly item: T;
  readonly reason: "unrunnable-iteration" | "ambiguous-feature-name";
}

export type TestSelectionPlan<T> =
  | {
      readonly kind: "ready";
      readonly selections: readonly {
        readonly item: T;
        readonly target: Readonly<TestTarget>;
      }[];
      readonly refused: readonly RefusedSelection<T>[];
    }
  | {
      readonly kind: "unsupported";
      readonly item: T;
      readonly reason: "incomplete-suite" | "unrepresentable-selection";
    };

/**
 * Plans the entire request before execution. Only whole suites and known
 * features have structural server targets; individual runtime rows do not.
 * Server names come from discovery descriptors, never opaque UI item IDs.
 * The execution backend owns interpretation of test-name filters.
 * `completeChildren` must be a discovery-owned feature snapshot, with undefined
 * meaning incomplete and [] meaning known empty. Never derive it from the live
 * children collection or canResolveChildren.
 */
export function planRunItems<T extends TestItemLike & { readonly parent?: T }>(
  include: readonly T[] | undefined,
  exclude: readonly T[] | undefined,
  roots: ItemCollection<T>,
  describe: (item: T) => TestSelectionDescriptor<T>,
): TestSelectionPlan<T> {
  const rootItems = new Set<T>();
  roots.forEach((item) => rootItems.add(item));
  const requested = new Set(include ?? rootItems);
  if (!requested.size) {
    return { kind: "ready", selections: [], refused: [] };
  }
  const excluded = new Set(exclude);
  const paths = new Map<T, readonly T[]>();
  const descriptors = new Map<T, TestSelectionDescriptor<T>>();
  const unsupported = (
    item: T,
    reason:
      | "incomplete-suite"
      | "unrepresentable-selection" = "unrepresentable-selection",
  ): TestSelectionPlan<T> => ({ kind: "unsupported", item, reason });
  const descriptor = (item: T): TestSelectionDescriptor<T> => {
    const value = descriptors.get(item) ?? describe(item);
    descriptors.set(item, value);
    return value;
  };

  for (const item of [...requested, ...excluded]) {
    const path: T[] = [];
    const visited = new Set<T>();
    let current: T | undefined = item;
    while (current) {
      if (visited.has(current)) {
        return unsupported(item);
      }
      visited.add(current);
      path.push(current);
      current = current.parent;
    }
    if (excluded.has(item) && !rootItems.has(path[path.length - 1])) {
      return unsupported(item);
    }
    paths.set(item, path);
  }
  const selected = [...requested].filter((item) => {
    const path = paths.get(item)!;
    return (
      !path.some((ancestor) => excluded.has(ancestor)) &&
      !path.slice(1).some((ancestor) => requested.has(ancestor))
    );
  });
  const exclusions = [...excluded].filter(
    (item) =>
      !paths
        .get(item)!
        .slice(1)
        .some((ancestor) => excluded.has(ancestor)),
  );
  const exclusionsBySuite = new Map<T, T[]>();
  for (const item of exclusions) {
    const path = paths.get(item)!;
    const suite = path[path.length - 1];
    const entries = exclusionsBySuite.get(suite) ?? [];
    entries.push(item);
    exclusionsBySuite.set(suite, entries);
  }
  const selections: { item: T; target: Readonly<TestTarget> }[] = [];
  const refused: RefusedSelection<T>[] = [];
  const targets = new Map<T, Map<string | undefined, T>>();
  // Only built for a suite that must be split around exclusions.
  const completeFeatures = new Map<
    T,
    | {
        byName: ReadonlyMap<string, readonly T[]>;
        all: ReadonlySet<T>;
      }
    | undefined
  >();
  const excludedFeatures = new Map<
    T,
    {
      byName: ReadonlyMap<string, { feature: T; entry: T }>;
      rows: ReadonlyMap<T, T>;
      firstRow: T | undefined;
    }
  >();
  const append = (item: T, target: Readonly<TestTarget>): boolean => {
    const suite = item.parent ?? item;
    const suiteTargets = targets.get(suite) ?? new Map<string | undefined, T>();
    const previous = suiteTargets.get(target.test);
    if (previous) {
      return previous === item;
    }
    suiteTargets.set(target.test, item);
    targets.set(suite, suiteTargets);
    selections.push({ item, target });
    return true;
  };

  for (const item of selected) {
    const path = paths.get(item)!;
    const suite = path[path.length - 1];
    const suiteDescriptor = descriptor(suite);
    const itemDescriptor = descriptor(item);
    if (
      !rootItems.has(suite) ||
      suiteDescriptor.kind !== "suite" ||
      (item !== suite &&
        (item.parent !== suite || itemDescriptor.kind !== "feature"))
    ) {
      return unsupported(item);
    }
    const target: Readonly<TestTarget> =
      itemDescriptor.kind === "feature"
        ? { suite: suiteDescriptor.suite, test: itemDescriptor.test }
        : { suite: suiteDescriptor.suite };

    const relevant = exclusionsBySuite.get(suite) ?? [];
    // The discovery snapshot only matters when something is excluded from this
    // suite. Without exclusions a whole suite runs as one target, whatever its
    // children are called (overloaded methods share a name).
    if (relevant.length && !completeFeatures.has(suite)) {
      const children = suiteDescriptor.completeChildren;
      const byName = new Map<string, T[]>();
      const all = new Set<T>();
      for (const child of children ?? []) {
        const childDescriptor = descriptor(child);
        if (child.parent !== suite || childDescriptor.kind !== "feature") {
          return unsupported(child);
        }
        const group = byName.get(childDescriptor.test) ?? [];
        if (!all.has(child)) group.push(child);
        all.add(child);
        byName.set(childDescriptor.test, group);
      }
      completeFeatures.set(
        suite,
        children === undefined ? undefined : { byName, all },
      );
    }
    const features = completeFeatures.get(suite);
    if (item !== suite && features && !features.all.has(item)) {
      return unsupported(item);
    }
    let exclusionIndex = excludedFeatures.get(suite);
    if (!exclusionIndex) {
      const byName = new Map<string, { feature: T; entry: T }>();
      const rows = new Map<T, T>();
      let firstRow: T | undefined;
      // Validate once per selected suite. Do not interpret unrelated roots'
      // descriptors or rescan all exclusions for every selected feature.
      for (const entry of relevant) {
        const entryPath = paths.get(entry)!;
        const feature = entryPath[entryPath.length - 2];
        if (!feature) return unsupported(entry);
        const featureDescriptor = descriptor(feature);
        if (
          featureDescriptor.kind !== "feature" ||
          (features !== undefined && !features.all.has(feature))
        ) {
          return unsupported(entry);
        }
        const name = featureDescriptor.test;
        if (!byName.has(name)) byName.set(name, { feature, entry });
        if (entry !== feature) {
          if (!rows.has(feature)) rows.set(feature, entry);
          firstRow ??= entry;
        }
      }
      exclusionIndex = { byName, rows, firstRow };
      excludedFeatures.set(suite, exclusionIndex);
    }
    if (item === suite && exclusionIndex.firstRow) {
      return unsupported(exclusionIndex.firstRow);
    }
    if (itemDescriptor.kind === "feature") {
      const alias = exclusionIndex.byName.get(itemDescriptor.test);
      if (alias && alias.feature !== item) {
        // The server filters by name, so it cannot run one of two same-named
        // features (overloads) without the other.
        refused.push({ item, reason: "ambiguous-feature-name" });
        continue;
      }
      const row = exclusionIndex.rows.get(item);
      if (row) return unsupported(row);
    }

    if (item === suite && relevant.length) {
      if (features === undefined) {
        return unsupported(item, "incomplete-suite");
      }
      // One target per feature name. Same-named features (overloads) run
      // together, which is exact unless only some of them are excluded; then
      // this suite alone is refused because a name filter cannot split them.
      const remaining: { child: T; test: string }[] = [];
      let ambiguous = false;
      for (const [test, group] of features.byName) {
        const kept = group.filter((child) => !excluded.has(child));
        if (kept.length === group.length)
          remaining.push({ child: kept[0], test });
        else if (kept.length) ambiguous = true;
      }
      if (ambiguous) {
        refused.push({ item, reason: "ambiguous-feature-name" });
        continue;
      }
      for (const { child, test } of remaining) {
        if (!append(child, { suite: suiteDescriptor.suite, test })) {
          return unsupported(child);
        }
      }
    } else if (!append(item, target)) {
      return unsupported(item);
    }
  }
  return { kind: "ready", selections, refused };
}
