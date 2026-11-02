export interface ServerVersionSources {
  /** `serverInfo.version` from the running server's initialize result. */
  reported: string | undefined;
  /** `groovy.server.path`; a custom JAR's version is only known once it runs. */
  serverPath: string | undefined;
  /** Reads the release tag recorded next to the bundled JAR (server/.gls-version). */
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
    return sources.readInstalledTag().trim() || "unknown";
  } catch {
    return "unknown";
  }
}
