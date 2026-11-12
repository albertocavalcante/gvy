// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

export interface JarProvenance {
  version: string;
  gitSha: string;
  dirty: boolean | null;
  dirtyHash: string;
}

export interface VersionRecord extends JarProvenance {
  source: "local" | "release" | "reused";
  tag: string;
}

export declare function parseVersionProperties(
  text: string,
): Record<string, string>;
export declare function provenanceFromProperties(
  props: Record<string, string>,
): JarProvenance;
export declare function readJarProvenance(
  jarPath: string,
): JarProvenance | null;
export declare function buildVersionRecord(input: {
  tag: string;
  source: VersionRecord["source"];
  provenance: JarProvenance | null;
}): VersionRecord;
export declare function describeRecord(record: VersionRecord): string;
export declare function requireFresh(env: NodeJS.ProcessEnv): boolean;
export declare function assessLocalJar(
  provenance: JarProvenance | null,
  head: string | null,
  checkoutDirty?: boolean | null,
): {
  state: "fresh" | "dirty" | "stale" | "unverifiable";
  reason: string;
};
