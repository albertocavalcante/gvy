// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as path from "path";

interface ScopeDocument {
  uri: { scheme: string; fsPath: string };
  version: number;
  isDirty: boolean;
}

export function inScope(directory: string, document: ScopeDocument): boolean {
  if (document.uri.scheme !== "file") return false;
  const relative = path.relative(directory, document.uri.fsPath);
  return (
    relative !== ".." &&
    !relative.startsWith(`..${path.sep}`) &&
    !path.isAbsolute(relative)
  );
}

export function scopeChanged<T extends ScopeDocument>(
  directory: string,
  saved: Map<T, number>,
  open: readonly T[],
): boolean {
  return (
    [...saved].some(
      ([document, version]) => document.version !== version || document.isDirty,
    ) ||
    open.some((document) => inScope(directory, document) && document.isDirty)
  );
}
