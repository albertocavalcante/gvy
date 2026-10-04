export declare const PINNED_RELEASE_TAG: string;

export declare function deriveSelection(options: {
  tag?: string | null;
  channel?: string | null;
  nightly?: boolean;
  latest?: boolean;
}): { type: "tag"; tag: string } | { type: "nightly" | "latest" | "pinned" };

export declare function requireChecksum(
  value: unknown,
  artifact: string,
): string;
