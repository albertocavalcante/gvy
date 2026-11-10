import { workspace, ConfigurationChangeEvent } from "vscode";

export interface GroovyConfiguration {
  javaHome: string | undefined;
  traceServer: "off" | "messages" | "verbose";
  maxNumberOfProblems: number;
  serverPath: string | undefined;
  /** Raw `groovy.server.vmargs`; sanitized when the command line is built. */
  serverVmArgs: unknown;
  /** Seconds to wait for the server to start; 0 waits indefinitely. */
  startTimeoutSeconds: number;
}

/**
 * Gets the current Groovy configuration
 */
export function getConfiguration(): GroovyConfiguration {
  const config = workspace.getConfiguration("groovy");

  return {
    javaHome:
      config.get<string>("languageServer.javaHome") ||
      config.get<string>("java.home"),
    traceServer: config.get<"off" | "messages" | "verbose">(
      "trace.server",
      "off",
    ),
    maxNumberOfProblems: config.get<number>("server.maxNumberOfProblems", 100),
    serverPath: config.get<string>("server.path"),
    serverVmArgs: config.get<unknown>("server.vmargs"),
    startTimeoutSeconds: startTimeoutFrom(
      config.get<unknown>("server.startTimeoutSeconds"),
    ),
  };
}

const DEFAULT_START_TIMEOUT_SECONDS = 120;

/** setTimeout clamps longer delays (2^31-1 ms) to 1 ms. */
const MAX_START_TIMEOUT_SECONDS = 2147483;

function startTimeoutFrom(value: unknown): number {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    return DEFAULT_START_TIMEOUT_SECONDS;
  }
  // Longer than ~24.8 days is "no limit" in practice.
  return value > MAX_START_TIMEOUT_SECONDS ? 0 : value;
}

/**
 * Every contributed `groovy.*` setting belongs to exactly one of these lists
 * (enforced by a unit test against package.json), so a new setting cannot be
 * silently ignored on change.
 */

/** Read by the server only at startup (or used to launch it). */
export const RESTART_REQUIRED_SETTINGS: readonly string[] = [
  "languageServer.javaHome",
  "java.home",
  "server.path",
  "server.vmargs",
  "server.logLevel",
  "languageServer.engine",
  "compilation.mode",
  "repl.enabled",
  "repl.maxSessions",
  "repl.sessionTimeoutMinutes",
  "gradle.buildStrategy",
  "gradle.targetClasspaths.enabled",
];

/** Applied by the server through workspace/didChangeConfiguration. */
export const SERVER_DYNAMIC_SETTINGS: readonly string[] = [
  "server.maxNumberOfProblems",
  "format.enable",
  "compilation.incrementalThreshold",
  "compilation.maxWorkspaceFiles",
  "compilation.cacheMaxEntries",
  "codenarc.enabled",
  "codenarc.propertiesFile",
  "codenarc.autoDetect",
  "jenkins.filePatterns",
  "jenkins.sharedLibraries",
  "jenkins.gdslPaths",
  "jenkins.gdslExecution.enabled",
  "jenkins.pluginsTxtPath",
  "jenkins.plugins",
  "jenkins.includeDefaultPlugins",
];

/** Read by the extension when needed; the server does not use them. */
export const CLIENT_ONLY_SETTINGS: readonly string[] = [
  "project.javaHome",
  "jdk.suppressMismatchWarning",
  "configuration.runtimes",
  // vscode-languageclient reads <client id>.trace.server itself.
  "trace.server",
  // Read at the next start.
  "server.startTimeoutSeconds",
  "statusBar.show",
  "statusBar.clickAction",
  "update.checkOnStartup",
  "update.checkIntervalHours",
  "update.notifications",
  "ai.tools.enabled",
  "ai.tools.allowed",
  "codelens.test.enabled",
  "codelens.test.source",
];

function affectsAny(
  event: ConfigurationChangeEvent,
  keys: readonly string[],
): boolean {
  return keys.some((key) => event.affectsConfiguration(`groovy.${key}`));
}

/**
 * Checks if a configuration change requires a server restart
 */
export function requiresServerRestart(
  event: ConfigurationChangeEvent,
): boolean {
  return affectsAny(event, RESTART_REQUIRED_SETTINGS);
}

/**
 * Checks if a configuration change can be applied dynamically via didChangeConfiguration
 */
export function canBeAppliedDynamically(
  event: ConfigurationChangeEvent,
): boolean {
  return affectsAny(event, SERVER_DYNAMIC_SETTINGS);
}

/**
 * Update notification level
 */
export type UpdateNotificationLevel = "off" | "onlyWhenOutdated" | "always";

/**
 * Update configuration
 */
export interface UpdateConfiguration {
  checkOnStartup: boolean;
  checkIntervalHours: number;
  notifications: UpdateNotificationLevel;
}

/**
 * Gets the update configuration
 */
export function getUpdateConfiguration(): UpdateConfiguration {
  const config = workspace.getConfiguration("groovy");

  return {
    checkOnStartup: config.get<boolean>("update.checkOnStartup", true),
    checkIntervalHours: Math.max(
      1,
      config.get<number>("update.checkIntervalHours", 24),
    ),
    notifications: config.get<UpdateNotificationLevel>(
      "update.notifications",
      "onlyWhenOutdated",
    ),
  };
}
