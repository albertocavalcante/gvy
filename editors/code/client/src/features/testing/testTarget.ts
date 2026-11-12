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

/**
 * Resolves the suite/test pair for an item from the item tree.
 *
 * Suites are root items whose id is the fully qualified class name (which
 * contains dots), and Spock feature names may contain dots too, so the id
 * alone cannot be split reliably. Items below a feature (dynamic iterations)
 * run their enclosing feature.
 */
export function resolveTestTarget(item: TestItemLike): TestTarget {
  if (!item.parent) {
    return { suite: item.id };
  }

  let feature = item;
  while (feature.parent?.parent) {
    feature = feature.parent;
  }
  const suite = feature.parent!.id;
  const prefix = `${suite}.`;
  const test = feature.id.startsWith(prefix)
    ? feature.id.slice(prefix.length)
    : feature.id;
  return { suite, test };
}

/**
 * Returns the items a run request targets. VS Code passes `include: undefined`
 * for "Run All", which means every root item; `exclude` always applies.
 */
export function selectRunItems<T>(
  include: readonly T[] | undefined,
  exclude: readonly T[] | undefined,
  roots: ItemCollection<T>,
): T[] {
  let items: T[];
  if (include) {
    items = [...include];
  } else {
    items = [];
    roots.forEach((item) => items.push(item));
  }
  if (!exclude?.length) {
    return items;
  }
  const excluded = new Set(exclude);
  return items.filter((item) => !excluded.has(item));
}
