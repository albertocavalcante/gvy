// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as vscode from "vscode";
import { workspace, ExtensionContext, window, commands } from "vscode";
import {
  CloseAction,
  CloseHandlerResult,
  ErrorHandler,
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
  State,
} from "vscode-languageclient/node";
import {
  validateJava,
  showJavaError,
  getJavaExecutable,
} from "../java/validator";
import { setClient, getStatusBarManager } from "../ui/statusBar";
import { getConfiguration } from "../configuration/settings";
import { buildJavaArgs, envJavaOptions } from "./launchArgs";
import { toProtocolUri } from "./uri";
import { ServerResolver } from "../services/ServerResolver";
import { pipeServerStderr, pipeServerStdout } from "./stderrRouter";

/**
 * Jenkins shared library configuration
 */
interface JenkinsSharedLibrary {
  name: string;
  jar: string;
  sourcesJar?: string;
}

/**
 * LSP Server initialization options.
 *
 * The Groovy LSP expects a flat map of string keys to values (not a nested object).
 * See groovy-lsp `ServerConfiguration.fromMap(...)` / `JenkinsConfiguration.fromMap(...)`.
 */
type LspInitializationOptions = Record<string, unknown>;

// `**/Jenkinsfile` matches the extensionless pipeline file, which
// `*.Jenkinsfile` alone never did.
const GROOVY_FILE_EVENTS_GLOB =
  "{**/*.{groovy,gvy,gy,gsh,gradle,Jenkinsfile},**/Jenkinsfile}";

let client: LanguageClient | undefined;
let context: ExtensionContext | undefined;
/** Server log channel, remembered so restarts keep writing to the same one. */
let serverOutputChannel: vscode.LogOutputChannel | undefined;
/** Created lazily once; recreating it on every start leaked channels. */
let traceOutputChannel: vscode.LogOutputChannel | undefined;
/** Resources owned by the current client instance (e.g. its file watcher). */
let clientResources: vscode.Disposable[] = [];
/**
 * Start/stop/restart are serialized: vscode-languageclient cannot stop a
 * client that is still starting, so overlapping calls used to orphan a JVM.
 */
let lifecycle: Promise<unknown> = Promise.resolve();

const startedListeners = new Set<() => void>();

/**
 * Registers a callback that runs after each successful (re)start. Features
 * that query the server once (e.g. test discovery) use it to refresh, since
 * they are registered before the server is up.
 */
export function onClientStarted(listener: () => void): vscode.Disposable {
  startedListeners.add(listener);
  return { dispose: () => startedListeners.delete(listener) };
}

/**
 * Error handler that defers to the client's default (restart a crashed server
 * a few times) until the client is abandoned, after which the closed
 * connection must not respawn the server we just killed.
 */
function isThenable<T>(value: T | PromiseLike<T>): value is PromiseLike<T> {
  return typeof (value as PromiseLike<T> | null)?.then === "function";
}

function abandonableErrorHandler(
  fallback: () => ErrorHandler,
  isAbandoned: () => boolean,
  beforeRestart: () => void = () => undefined,
): ErrorHandler {
  return {
    error: (error, message, count) => fallback().error(error, message, count),
    closed: () => {
      if (isAbandoned()) {
        return { action: CloseAction.DoNotRestart, handled: true };
      }
      const refresh = (result: CloseHandlerResult): CloseHandlerResult => {
        if (result.action === CloseAction.Restart) {
          beforeRestart();
        }
        return result;
      };
      const result = fallback().closed();
      return isThenable(result)
        ? Promise.resolve(result).then(refresh)
        : refresh(result);
    },
  };
}

/**
 * Resolves with `start`, or rejects after `seconds` (0 disables the limit).
 * vscode-languageclient cannot stop a client that is still starting, so
 * without a limit a server that never answers `initialize` would hold the
 * serialized lifecycle, and with it Stop and Restart, forever.
 */
function withStartTimeout(
  start: Promise<void>,
  seconds: number,
): Promise<void> {
  if (!(seconds > 0)) {
    return start;
  }
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(
      () =>
        reject(
          new Error(
            `Server did not start within ${seconds} seconds (groovy.server.startTimeoutSeconds)`,
          ),
        ),
      seconds * 1000,
    );
  });
  return Promise.race([start, timeout]).finally(() => clearTimeout(timer));
}

const LATE_SPAWN_POLL_MS = 250;
const LATE_SPAWN_POLL_LIMIT = 120; // 30 s

/**
 * Ends a client whose start timed out. stop() refuses a starting client, so
 * its JVM is killed directly and the closed connection fails the start. The
 * JVM may not exist yet (the client spawns it after resolving its working
 * directory), so a late spawn is killed when it appears, and a start that
 * still completes is stopped.
 */
function abandonStartingClient(
  target: LanguageClient,
  starting: Promise<void>,
): void {
  starting.then(
    () => target.stop().catch(() => undefined),
    () => undefined,
  );
  let polls = 0;
  const killWhenSpawned = () => {
    const proc = target.serverProcess;
    if (proc) {
      proc.kill();
    } else if (
      target.state === State.Starting &&
      ++polls < LATE_SPAWN_POLL_LIMIT
    ) {
      setTimeout(killWhenSpawned, LATE_SPAWN_POLL_MS).unref?.();
    }
  };
  killWhenSpawned();
}

function serialize<T>(operation: () => Promise<T>): Promise<T> {
  const result = lifecycle.then(operation, operation);
  lifecycle = result.catch(() => undefined);
  return result;
}

export function initializeClient(extensionContext: ExtensionContext) {
  context = extensionContext;
}

/**
 * Gets the current Language Client instance
 */
export function getClient(): LanguageClient | undefined {
  return client;
}

/**
 * Gets the path to the Groovy Language Server JAR
 */
async function getServerJarPath(): Promise<string> {
  if (!context) {
    throw new Error("Extension context not initialized");
  }

  const config = getConfiguration();
  const resolver = new ServerResolver();

  try {
    return await resolver.resolve(context, { serverPath: config.serverPath });
  } catch (error) {
    const message =
      `Groovy Language Server JAR not found.\n\n` +
      `The JAR should be automatically downloaded during extension installation. ` +
      `If you have a custom path configured, please verify it exists. ` +
      `If this error persists, please report it as an issue.`;

    window.showErrorMessage(message, "Open Issues").then((selection) => {
      if (selection === "Open Issues") {
        commands.executeCommand(
          "vscode.open",
          "https://github.com/albertocavalcante/gvy/issues",
        );
      }
    });

    throw error;
  }
}

function currentJavaArgs(jarPath: string, javaVersion?: number): string[] {
  const config = getConfiguration();
  return buildJavaArgs({
    jarPath,
    javaVersion,
    vmArgs: config.serverVmArgs,
    useDefaultVmArgs: config.serverVmArgsDefaults,
    env: envJavaOptions(process.env),
  });
}

/** The args array owned by one client's ServerOptions, and its inputs. */
interface LaunchArgs {
  args: string[];
  jarPath: string;
  javaVersion?: number;
}

/**
 * The languageclient re-reads the (shared) args array each time it respawns
 * a crashed server, so rebuilding it in place makes automatic restarts use
 * the current `groovy.server.vmargs` instead of the activation-time value.
 */
function refreshLaunchArgs(launch: LaunchArgs): void {
  launch.args.splice(
    0,
    launch.args.length,
    ...currentJavaArgs(launch.jarPath, launch.javaVersion),
  );
}

/**
 * Creates server options for launching the Groovy Language Server
 */
async function createServerOptions(): Promise<{
  serverOptions: ServerOptions;
  launch: LaunchArgs;
}> {
  const jarPath = await getServerJarPath();
  const javaValidation = await validateJava();

  if (!javaValidation.isValid) {
    // Non-modal notifications only settle when dismissed; do not hold the
    // start (and therefore activation) hostage to the user.
    showJavaError(javaValidation).catch((error) =>
      console.error("Failed to show Java error:", error),
    );
    throw new Error(`Java validation failed: ${javaValidation.error}`);
  }

  const javaExecutable = getJavaExecutable(javaValidation.resolution);

  // JVM options (defaults + user-configurable) go before -jar
  const javaVersion = javaValidation.resolution?.version;
  const args = currentJavaArgs(jarPath, javaVersion);
  const launch: LaunchArgs = { args, jarPath, javaVersion };

  // Server launch options
  const serverOptions: ServerOptions = {
    run: {
      command: javaExecutable,
      args: args,
      options: {
        env: process.env,
        cwd: workspace.workspaceFolders?.[0]?.uri.fsPath,
      },
    },
    debug: {
      command: javaExecutable,
      args: args,
      options: {
        env: process.env,
        cwd: workspace.workspaceFolders?.[0]?.uri.fsPath,
      },
    },
  };

  return { serverOptions, launch };
}

/**
 * Builds a flat settings map for the Groovy LSP server.
 *
 * Note: VS Code settings are namespaced under `groovy.*`, but the Groovy LSP uses:
 * - `groovy.*` keys for core server settings
 * - `jenkins.*` keys for Jenkins configuration
 */
export function buildServerSettingsMap(): Record<string, unknown> {
  const config = workspace.getConfiguration("groovy");

  const settings: Record<string, unknown> = {
    "groovy.server.maxNumberOfProblems": config.get<number>(
      "server.maxNumberOfProblems",
      100,
    ),
    "groovy.server.logLevel": config.get<string>("server.logLevel", "info"),
    "groovy.trace.server": config.get<"off" | "messages" | "verbose">(
      "trace.server",
      "off",
    ),

    "groovy.languageServer.engine": config.get<"core" | "native">(
      "languageServer.engine",
      "native",
    ),

    "groovy.compilation.mode": config.get<"workspace" | "single-file">(
      "compilation.mode",
      "workspace",
    ),
    "groovy.compilation.incrementalThreshold": config.get<number>(
      "compilation.incrementalThreshold",
      50,
    ),
    "groovy.compilation.maxWorkspaceFiles": config.get<number>(
      "compilation.maxWorkspaceFiles",
      500,
    ),
    "groovy.compilation.cacheMaxEntries": config.get<number>(
      "compilation.cacheMaxEntries",
      300,
    ),

    "groovy.repl.enabled": config.get<boolean>("repl.enabled", true),
    "groovy.repl.maxSessions": config.get<number>("repl.maxSessions", 10),
    "groovy.repl.sessionTimeoutMinutes": config.get<number>(
      "repl.sessionTimeoutMinutes",
      60,
    ),

    "groovy.codenarc.enabled": config.get<boolean>("codenarc.enabled", true),
    "groovy.codenarc.autoDetect": config.get<boolean>(
      "codenarc.autoDetect",
      true,
    ),
    "groovy.format.enable": config.get<boolean>("format.enable", true),

    // VS Code supplies the manifest default; the server applies the same default if this is absent.
    "jenkins.filePatterns": config.get<string[]>("jenkins.filePatterns"),
    "jenkins.sharedLibraries": config.get<JenkinsSharedLibrary[]>(
      "jenkins.sharedLibraries",
      [],
    ),
    "jenkins.gdslPaths": config.get<string[]>("jenkins.gdslPaths", []),
    "jenkins.gdslExecution.enabled": config.get<boolean>(
      "jenkins.gdslExecution.enabled",
      false,
    ),
    "jenkins.plugins": config.get<string[]>("jenkins.plugins", []),
    "jenkins.includeDefaultPlugins": config.get<boolean>(
      "jenkins.includeDefaultPlugins",
      true,
    ),

    "groovy.gradle.buildStrategy": config.get<"auto" | "gradle" | "maven">(
      "gradle.buildStrategy",
      "auto",
    ),
    "groovy.gradle.targetClasspaths.enabled": config.get<boolean>(
      "gradle.targetClasspaths.enabled",
      false,
    ),
    "groovy.gradle.daemon.idleTimeoutMinutes": config.get<number>(
      "gradle.daemon.idleTimeoutMinutes",
      10,
    ),
  };

  const javaHome = config.get<string>("java.home");
  if (typeof javaHome === "string" && javaHome.trim().length > 0) {
    settings["groovy.java.home"] = javaHome;
  }

  const codeNarcPropertiesFile = config.get<string>("codenarc.propertiesFile");
  if (
    typeof codeNarcPropertiesFile === "string" &&
    codeNarcPropertiesFile.trim().length > 0
  ) {
    settings["groovy.codenarc.propertiesFile"] = codeNarcPropertiesFile;
  }

  const pluginsTxtPath = config.get<string>("jenkins.pluginsTxtPath");
  if (typeof pluginsTxtPath === "string" && pluginsTxtPath.trim().length > 0) {
    settings["jenkins.pluginsTxtPath"] = pluginsTxtPath;
  }

  return settings;
}

/**
 * Builds initialization options from workspace configuration
 */
function buildInitializationOptions(): LspInitializationOptions {
  return buildServerSettingsMap();
}

/**
 * The trace channel always exists so tracing can be turned on at runtime:
 * vscode-languageclient traces when this channel's log level is Trace, with
 * the verbosity from `groovy.trace.server`.
 */
function getTraceOutputChannel(): vscode.LogOutputChannel {
  if (!traceOutputChannel) {
    traceOutputChannel = window.createOutputChannel(
      "Groovy Language Server Trace",
      { log: true },
    );
    context?.subscriptions.push(traceOutputChannel);
  }
  return traceOutputChannel;
}

function createClientOptions(
  fileWatcher: vscode.FileSystemWatcher,
  outputChannel?: vscode.LogOutputChannel,
): LanguageClientOptions {
  const clientOptions: LanguageClientOptions = {
    // Register the server for Groovy and Jenkinsfile documents
    documentSelector: [
      { scheme: "file", language: "groovy" },
      { scheme: "file", language: "jenkinsfile" },
      { scheme: "untitled", language: "groovy" },
      { scheme: "untitled", language: "jenkinsfile" },
    ],
    synchronize: {
      // Notify the server about file changes to Groovy files in the workspace
      fileEvents: fileWatcher,
    },
    initializationOptions: buildInitializationOptions(),
    outputChannel: outputChannel,
    outputChannelName: outputChannel ? undefined : "Groovy Language Server",
    // Keep the server's own log severity: the default logs all stderr as errors.
    stdioOptions: {
      stdout: (input, channel) => pipeServerStdout(input, channel),
      stderr: (input, channel) => pipeServerStderr(input, channel),
    },
    traceOutputChannel: getTraceOutputChannel(),
    // URI converters for Windows compatibility
    uriConverters: {
      code2Protocol: (value) => toProtocolUri(value.toString()),
      protocol2Code: (value) => vscode.Uri.parse(value),
    },
  };

  return clientOptions;
}

/**
 * Starts the Language Client. The output channel, once given, is reused by
 * later restarts.
 */
export function startClient(
  outputChannel?: vscode.LogOutputChannel,
): Promise<void> {
  if (outputChannel) {
    serverOutputChannel = outputChannel;
  }
  return serialize(doStart);
}

/**
 * Stops the Language Client
 */
export function stopClient(): Promise<void> {
  return serialize(doStop);
}

/**
 * Restarts the Language Client
 */
export async function restartClient(): Promise<void> {
  try {
    await serialize(async () => {
      await doStop();
      await doStart();
    });
    window.showInformationMessage(
      "Groovy Language Server restarted successfully",
    );
  } catch (error) {
    const message = `Failed to restart Groovy Language Server: ${error instanceof Error ? error.message : "Unknown error"}`;
    console.error(message);
    window.showErrorMessage(message);
  }
}

async function doStart(): Promise<void> {
  if (client?.isRunning()) {
    return;
  }
  await doStop();

  const fileWatcher = workspace.createFileSystemWatcher(
    GROOVY_FILE_EVENTS_GLOB,
  );
  clientResources.push(fileWatcher);

  try {
    const { serverOptions, launch } = await createServerOptions();
    const clientOptions = createClientOptions(fileWatcher, serverOutputChannel);
    let abandoned = false;
    let defaultErrorHandler: ErrorHandler | undefined;
    clientOptions.errorHandler = abandonableErrorHandler(
      () => (defaultErrorHandler ??= created.createDefaultErrorHandler()),
      () => abandoned,
      () => refreshLaunchArgs(launch),
    );

    // The id is the configuration section the client reads
    // `<id>.trace.server` from, so it must be "groovy".
    const created = new LanguageClient(
      "groovy",
      "Groovy Language Server",
      serverOptions,
      clientOptions,
    );
    client = created;

    // Update status bar with new client
    setClient(client);
    if (serverOutputChannel) {
      getStatusBarManager()?.setOutputChannel(serverOutputChannel);
    }

    const starting = created.start();
    try {
      await withStartTimeout(starting, getConfiguration().startTimeoutSeconds);
    } catch (error) {
      if (created.state === State.Starting) {
        abandoned = true;
        abandonStartingClient(created, starting);
      }
      throw error;
    }
    const version = created.initializeResult?.serverInfo?.version;
    if (version) {
      getStatusBarManager()?.setServerVersion(version);
    }
    console.log("Groovy Language Server started successfully");
    startedListeners.forEach((listener) => {
      try {
        listener();
      } catch (error) {
        console.error("Client start listener failed:", error);
      }
    });
  } catch (error) {
    const message = `Failed to start Groovy Language Server: ${error instanceof Error ? error.message : "Unknown error"}`;
    console.error(message);
    window.showErrorMessage(message);
    await doStop();
    throw error;
  }
}

async function doStop(): Promise<void> {
  const current = client;
  client = undefined;
  setClient(undefined);
  const resources = clientResources;
  clientResources = [];
  try {
    if (current) {
      // Also stop clients that are not Running (start failed after the
      // JVM spawned): the node client kills its server process in
      // stop() even when the protocol shutdown itself throws.
      await current.stop().catch((error: unknown) => {
        if (current.isRunning()) {
          throw error;
        }
      });
      console.log("Groovy Language Server stopped");
    }
  } catch (error) {
    console.error("Error stopping Language Server:", error);
  } finally {
    resources.forEach((resource) => resource.dispose());
  }
}
