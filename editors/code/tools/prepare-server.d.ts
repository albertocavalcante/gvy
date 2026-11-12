// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

export declare const PINNED_RELEASE_TAG: string;

export declare function downloadFromUrl(
  url: string,
  expectedChecksum: string,
): Promise<void>;

export declare function deriveSelection(options: {
  tag?: string | null;
  channel?: string | null;
  nightly?: boolean;
  latest?: boolean;
}): { type: "tag"; tag: string } | { type: "nightly" | "latest" | "pinned" };

export declare function hasExplicitVersionSelection(options: {
  tag?: string | null;
  channel?: string | null;
  nightly?: boolean;
  latest?: boolean;
}): boolean;

export declare function requireChecksum(
  value: unknown,
  artifact: string,
): string;

export declare function installVerifiedJar(
  sourcePath: string,
  destPath: string,
  expectedChecksum: string | null,
  validate?: (filePath: string) => void,
): Promise<void>;

export declare function ensureFreshLocalJar(
  jarPath: string,
  deps: {
    readProvenance: (jar: string) => {
      version: string;
      gitSha: string;
      dirty: boolean | null;
      dirtyHash: string;
    } | null;
    getHead: () => string | null;
    getCheckoutDirty?: () => boolean | null;
    isExplicit?: (jar: string) => boolean;
    strict: boolean;
    rebuild: (() => void) | null;
    findJar: () => string | null;
    warn: (message: string) => void;
  },
): string;
