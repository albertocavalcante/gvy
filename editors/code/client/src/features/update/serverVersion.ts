// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

export interface ServerVersionSources {
  /** `serverInfo.version` from the running server's initialize result. */
  reported: string | undefined;
  /** `groovy.server.path`; a custom JAR's version is only known once it runs. */
  serverPath: string | undefined;
  /** Reads the record next to the bundled JAR (server/.gls-version). */
  readInstalledTag: () => string;
}

/**
 * The version update checks compare against gvy releases. That is the
 * server's own version, not the extension's: the two are released
 * independently.
 */
export function resolveServerVersion(sources: ServerVersionSources): string {
  if (sources.reported) {
    return sources.reported;
  }
  if (sources.serverPath?.trim()) {
    return "unknown";
  }
  try {
    return parseInstalledTag(sources.readInstalledTag()) || "unknown";
  } catch {
    return "unknown";
  }
}

/**
 * server/.gls-version is a JSON record ({ tag, version, gitSha, ... }); jars
 * installed before provenance was recorded left the bare tag instead.
 */
export function parseInstalledTag(content: string): string {
  const trimmed = content.trim();
  if (!trimmed.startsWith("{")) {
    return trimmed;
  }
  try {
    const record = JSON.parse(trimmed) as { tag?: unknown; version?: unknown };
    const tag = record.tag ?? record.version;
    return typeof tag === "string" ? tag.trim() : "";
  } catch {
    return "";
  }
}
