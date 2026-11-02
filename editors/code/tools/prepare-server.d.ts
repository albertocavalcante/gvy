export declare const PINNED_RELEASE_TAG: string;

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
