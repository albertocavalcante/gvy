// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as fs from "fs/promises";
import { constants as fsConstants } from "fs";
import { createHash } from "crypto";
import { execFile } from "child_process";
import { promisify } from "util";
import * as path from "path";
import * as vscode from "vscode";
import { getClient } from "../../server/client";
import { executePlan, Plan, ProcessPlan } from "./orchestrator";
import { awaitTask } from "./taskCompletion";
import { qualifyWindowsArguments, shortenJavaArguments } from "./javaArguments";
import { inScope, scopeChanged } from "./scope";
import { resolveProjectJavaHome } from "./projectJava";
import { ArtifactQueue } from "./artifactQueue";

const execFileAsync = promisify(execFile);

async function verifiedBundledRuntime(
  context: vscode.ExtensionContext,
): Promise<string[]> {
  const artifacts = [
    [
      "groovy-4.0.33.jar",
      "5240a9151289115e4d447d6b6d4cb9ac8a9fc6374e079714dfeeffda42323be8",
    ],
    [
      "ivy-2.5.3.jar",
      "9502aa5aabf0b1484924a13ef4b5d24f121f7f91aab6768f11cc9d0906c0803b",
    ],
  ];
  try {
    const paths: string[] = [];
    for (const [name, checksum] of artifacts) {
      const file = context.asAbsolutePath(`runtime/${name}`);
      const data = await fs.readFile(file);
      if (createHash("sha256").update(data).digest("hex") !== checksum)
        return [];
      paths.push(file);
    }
    return paths;
  } catch {
    return [];
  }
}

interface FileTaskDefinition extends vscode.TaskDefinition {
  file: string;
  action: "run" | "compile";
  context?: string;
  arguments?: string[];
  jvmArguments?: string[];
  workingDirectory?: string;
  environment?: Record<string, string>;
  classpath?: string[];
  preparationTask?: string;
}

export function registerFileExecution(
  context: vscode.ExtensionContext,
  start: () => Promise<boolean>,
): void {
  let last:
    | { uri: vscode.Uri; entryPoint?: string; profile?: FileTaskDefinition }
    | undefined;
  const choices = new Map<
    string,
    { contextId?: string; entryPoint?: string }
  >();
  const artifactQueue = new ArtifactQueue();
  const globalRoot = path.join(
    context.globalStorageUri.fsPath,
    "file-execution",
  );
  const workspaceRoot =
    context.storageUri &&
    path.join(context.storageUri.fsPath, "file-execution");
  const isOwnedOutput = (output: string) =>
    (path.dirname(output) === globalRoot ||
      (workspaceRoot !== undefined &&
        path.dirname(output) === workspaceRoot)) &&
    path.basename(output).startsWith("result-");

  async function execute(
    action: "run" | "compile",
    input?: vscode.Uri | { uri: string; entryPoint?: string },
    forceContext = false,
    profile?: FileTaskDefinition,
    externalCancellation?: vscode.CancellationToken,
  ): Promise<boolean> {
    if (!vscode.workspace.isTrusted) {
      void vscode.window.showWarningMessage(
        "Trust this workspace to run or compile Groovy files.",
      );
      return false;
    }
    let document =
      input instanceof vscode.Uri
        ? await vscode.workspace.openTextDocument(input)
        : input?.uri
          ? await vscode.workspace.openTextDocument(vscode.Uri.parse(input.uri))
          : vscode.window.activeTextEditor?.document;
    if (!document) {
      void vscode.window.showWarningMessage("Open a Groovy file first.");
      return false;
    }
    if (!(await start())) return false;
    const client = getClient();
    if (!client) return false;
    let output = "";
    const bundledRuntime = await verifiedBundledRuntime(context);
    let keep = false;
    let success = false;
    let wasCancelled = false;
    try {
      await vscode.window.withProgress(
        {
          location: vscode.ProgressLocation.Notification,
          title: "Groovy file execution",
          cancellable: true,
        },
        async (_progress, cancellation) => {
          let selection =
            choices.get(document!.uri.toString()) ??
            context.workspaceState.get<{
              contextId?: string;
              entryPoint?: string;
            }>(`fileExecution.choice.${document!.uri.toString()}`) ??
            {};
          let forceContextChoice = forceContext;
          let forceEntryPoint = false;
          if (forceContext) selection = {};
          if (profile?.context)
            selection = { ...selection, contextId: profile.context };
          if (!(input instanceof vscode.Uri) && input?.entryPoint)
            selection = { ...selection, entryPoint: input.entryPoint };
          let preparedVersion: number | undefined;
          let scopeVersions = new Map<vscode.TextDocument, number>();
          let scopeDirectory: string | undefined;
          let savedScopeDirectory: string | undefined;
          let resolvedProjectHome: string | undefined;
          let projectHomeKey: string | undefined;
          const requestCancellation = new vscode.CancellationTokenSource();
          let compilationStarted = false;
          let operationFailed = false;
          let operationError: unknown;
          let currentTask: vscode.TaskExecution | undefined;
          let currentProbe: AbortController | undefined;
          const subscription = cancellation.onCancellationRequested(() => {
            wasCancelled = true;
            requestCancellation.cancel();
            currentProbe?.abort();
            currentTask?.terminate();
          });
          const externalSubscription =
            externalCancellation?.onCancellationRequested(() => {
              wasCancelled = true;
              requestCancellation.cancel();
              currentProbe?.abort();
              currentTask?.terminate();
            });
          const isCancelled = () =>
            cancellation.isCancellationRequested ||
            !!externalCancellation?.isCancellationRequested;
          let explicitPreparationDone = false;
          try {
            const result = await executePlan({
              trusted: () => vscode.workspace.isTrusted,
              save: async () => {
                const wasUntitled = document!.isUntitled;
                if (!(await document!.save())) return false;
                if (wasUntitled)
                  document = vscode.window.activeTextEditor?.document;
                if (
                  !document ||
                  document.isUntitled ||
                  document.uri.scheme !== "file"
                )
                  return false;
                const root =
                  workspaceRoot &&
                  vscode.workspace.getWorkspaceFolder(document.uri)
                    ? workspaceRoot
                    : globalRoot;
                await fs.mkdir(root, { recursive: true });
                output = await fs.mkdtemp(path.join(root, "result-"));
                if (action === "run") {
                  last = {
                    uri: document.uri,
                    entryPoint: selection.entryPoint,
                    profile: profile && {
                      type: "groovy-file",
                      file: profile.file,
                      action: "run",
                      context: profile.context,
                      arguments: profile.arguments?.slice(),
                      jvmArguments: profile.jvmArguments?.slice(),
                      workingDirectory: profile.workingDirectory,
                      environment: profile.environment && {
                        ...profile.environment,
                      },
                      classpath: profile.classpath?.slice(),
                      preparationTask: profile.preparationTask,
                    },
                  };
                }
                return true;
              },
              saveScope: async (directory) => {
                scopeDirectory = directory;
                savedScopeDirectory = undefined;
                for (const doc of vscode.workspace.textDocuments) {
                  const relative = path.relative(directory, doc.uri.fsPath);
                  if (
                    doc.isDirty &&
                    doc.uri.scheme === "file" &&
                    relative !== ".." &&
                    !relative.startsWith(`..${path.sep}`) &&
                    !path.isAbsolute(relative)
                  ) {
                    if (!(await doc.save())) return false;
                  }
                }
                scopeVersions = new Map(
                  vscode.workspace.textDocuments
                    .filter((doc) => inScope(directory, doc))
                    .map((doc) => [doc, doc.version]),
                );
                savedScopeDirectory = directory;
                return true;
              },
              prepare: async (token, launchToken) => {
                if (profile?.preparationTask && !explicitPreparationDone) {
                  const folder = vscode.workspace.getWorkspaceFolder(
                    document!.uri,
                  );
                  if (!folder)
                    throw new Error(
                      "A preparation task needs a workspace folder.",
                    );
                  for (const doc of vscode.workspace.textDocuments) {
                    const relative = path.relative(
                      folder.uri.fsPath,
                      doc.uri.fsPath,
                    );
                    if (
                      doc.uri.scheme === "file" &&
                      doc.isDirty &&
                      relative !== ".." &&
                      !relative.startsWith(`..${path.sep}`) &&
                      !path.isAbsolute(relative) &&
                      !(await doc.save())
                    )
                      throw new Error("Could not save a workspace document.");
                  }
                  scopeDirectory = folder.uri.fsPath;
                  scopeVersions = new Map(
                    vscode.workspace.textDocuments
                      .filter((doc) => inScope(scopeDirectory!, doc))
                      .map((doc) => [doc, doc.version]),
                  );
                  const candidates = (await vscode.tasks.fetchTasks()).filter(
                    (task) => {
                      if (
                        task.name !== profile.preparationTask ||
                        task.definition.type === "groovy-file"
                      )
                        return false;
                      return (
                        typeof task.scope !== "object" ||
                        task.scope.uri.toString() === folder.uri.toString()
                      );
                    },
                  );
                  if (candidates.length !== 1)
                    throw new Error(
                      candidates.length
                        ? `Preparation task is ambiguous: ${profile.preparationTask}`
                        : `Preparation task not found: ${profile.preparationTask}`,
                    );
                  const preparation = candidates[0];
                  if (
                    !(
                      preparation.execution instanceof vscode.ProcessExecution
                    ) &&
                    !(preparation.execution instanceof vscode.ShellExecution)
                  ) {
                    throw new Error(
                      "Choose a process or shell preparation task; compound and custom tasks do not report a reliable exit status.",
                    );
                  }
                  if (isCancelled()) throw new Error("Execution cancelled.");
                  const succeeded = await awaitTask(
                    vscode.tasks,
                    preparation,
                    (execution) => {
                      currentTask = execution;
                      if (isCancelled()) execution.terminate();
                    },
                    isCancelled,
                  );
                  currentTask = undefined;
                  if (!succeeded)
                    throw new Error(
                      "Preparation task failed or was cancelled.",
                    );
                  if (
                    scopeChanged(
                      scopeDirectory,
                      scopeVersions,
                      vscode.workspace.textDocuments,
                    )
                  ) {
                    throw new Error(
                      "A workspace document changed during preparation. Run again.",
                    );
                  }
                  explicitPreparationDone = true;
                }
                const config = vscode.workspace.getConfiguration(
                  "groovy.fileExecution",
                  document!.uri,
                );
                if (
                  savedScopeDirectory &&
                  scopeDirectory &&
                  scopeChanged(
                    scopeDirectory,
                    scopeVersions,
                    vscode.workspace.textDocuments,
                  )
                )
                  return {
                    kind: "unavailable",
                    reason:
                      "A build-scope document changed during preparation. Run again.",
                  };
                const projectJavaHome = vscode.workspace
                  .getConfiguration("groovy", document!.uri)
                  .get<string>("project.javaHome");
                if (savedScopeDirectory) {
                  const nextKey = JSON.stringify([
                    projectJavaHome,
                    globalThis.process.env.JAVA_HOME,
                    globalThis.process.env.PATH,
                  ]);
                  if (!resolvedProjectHome || projectHomeKey !== nextKey) {
                    const controller = new AbortController();
                    currentProbe = controller;
                    try {
                      resolvedProjectHome = await resolveProjectJavaHome(
                        projectJavaHome,
                        globalThis.process.env.JAVA_HOME,
                        async (home) => {
                          if (!path.isAbsolute(home)) return false;
                          try {
                            await fs.access(
                              path.join(
                                home,
                                "bin",
                                globalThis.process.platform === "win32"
                                  ? "java.exe"
                                  : "java",
                              ),
                              fsConstants.X_OK,
                            );
                            return true;
                          } catch {
                            return false;
                          }
                        },
                        async () => {
                          if (
                            isCancelled() ||
                            !vscode.workspace.isTrusted ||
                            scopeChanged(
                              scopeDirectory!,
                              scopeVersions,
                              vscode.workspace.textDocuments,
                            )
                          )
                            throw new Error(
                              "The build scope changed while selecting Java. Run again.",
                            );
                          const result = await execFileAsync(
                            "java",
                            ["-XshowSettings:properties", "-version"],
                            {
                              cwd: savedScopeDirectory,
                              env: globalThis.process.env,
                              timeout: 10000,
                              signal: controller.signal,
                            },
                          );
                          return result.stderr + result.stdout;
                        },
                      );
                      projectHomeKey = nextKey;
                    } finally {
                      currentProbe = undefined;
                    }
                  }
                  if (
                    isCancelled() ||
                    !vscode.workspace.isTrusted ||
                    vscode.workspace
                      .getConfiguration("groovy", document!.uri)
                      .get<string>("project.javaHome") !== projectJavaHome ||
                    scopeChanged(
                      scopeDirectory!,
                      scopeVersions,
                      vscode.workspace.textDocuments,
                    )
                  ) {
                    return {
                      kind: "unavailable",
                      reason:
                        "The build scope changed while selecting Java. Run again.",
                    };
                  }
                }
                const javaExecutable =
                  config.get<string>("javaExecutable") ||
                  (projectJavaHome
                    ? path.join(
                        projectJavaHome,
                        "bin",
                        process.platform === "win32" ? "java.exe" : "java",
                      )
                    : undefined);
                const plan = await client.sendRequest<Plan>(
                  "groovy/prepareFileExecution",
                  {
                    uri: document!.uri.toString(),
                    version: document!.version,
                    action,
                    trusted: vscode.workspace.isTrusted,
                    forceProject: forceContextChoice && !selection.contextId,
                    forceEntryPoint: forceEntryPoint && !selection.entryPoint,
                    ...selection,
                    preparationToken: token,
                    launchToken,
                    savedScopeDirectory,
                    options: {
                      outputDirectory: output,
                      bundledRuntime,
                      javaExecutable,
                      projectJavaHome: savedScopeDirectory
                        ? resolvedProjectHome
                        : projectJavaHome,
                      explicitRuntime: config.get<string[]>("runtime"),
                      localRuntime: config.get<string[]>("localRuntime"),
                      arguments:
                        profile?.arguments ??
                        config.get<string[]>("arguments", []),
                      jvmArguments:
                        profile?.jvmArguments ??
                        config.get<string[]>("jvmArguments", []),
                      workingDirectory:
                        profile?.workingDirectory ??
                        config.get<string>("workingDirectory"),
                      environment:
                        profile?.environment ??
                        config.get<Record<string, string>>("environment", {}),
                      classpath:
                        profile?.classpath ??
                        config.get<string[]>("classpath", []),
                    },
                  },
                  requestCancellation.token,
                );
                preparedVersion = document!.version;
                return plan;
              },
              clearInvalidSelection: async (code) => {
                if (
                  (code === "invalidContext" &&
                    (profile?.context || !selection.contextId)) ||
                  (code === "invalidEntryPoint" &&
                    ((!(input instanceof vscode.Uri) && input?.entryPoint) ||
                      !selection.entryPoint))
                )
                  return false;
                savedScopeDirectory = undefined;
                selection = { ...selection };
                if (code === "invalidContext") {
                  delete selection.contextId;
                  forceContextChoice = true;
                } else {
                  delete selection.entryPoint;
                  forceEntryPoint = true;
                }
                choices.set(document!.uri.toString(), selection);
                await context.workspaceState.update(
                  `fileExecution.choice.${document!.uri.toString()}`,
                  selection,
                );
                return true;
              },
              choose: async (plan) => {
                const picked = await vscode.window.showQuickPick(
                  plan.choices.map((choice) => ({
                    label: choice.label,
                    id: choice.id,
                  })),
                  {
                    placeHolder: `Select ${plan.choiceType === "context" ? "execution context" : "entry point"}`,
                  },
                );
                if (!picked) return false;
                if (
                  plan.choiceType === "context" &&
                  picked.id === "standalone" &&
                  !profile?.preparationTask
                ) {
                  scopeDirectory = undefined;
                  scopeVersions.clear();
                }
                savedScopeDirectory = undefined;
                if (plan.choiceType === "context") forceContextChoice = false;
                else forceEntryPoint = false;
                selection = {
                  ...selection,
                  [plan.choiceType === "context" ? "contextId" : "entryPoint"]:
                    picked.id,
                };
                if (
                  action === "run" &&
                  last &&
                  plan.choiceType === "entryPoint"
                )
                  last.entryPoint = picked.id;
                if (picked.id !== "inspect-project") {
                  choices.set(document!.uri.toString(), selection);
                  await context.workspaceState.update(
                    `fileExecution.choice.${document!.uri.toString()}`,
                    selection,
                  );
                }
                return true;
              },
              run: async (process: ProcessPlan, label: string) => {
                const inputsChanged = () =>
                  isCancelled() ||
                  !vscode.workspace.isTrusted ||
                  document!.version !== preparedVersion ||
                  document!.isDirty ||
                  (scopeDirectory !== undefined &&
                    scopeChanged(
                      scopeDirectory,
                      scopeVersions,
                      vscode.workspace.textDocuments,
                    ));
                if (inputsChanged()) return false;
                if (action === "compile" && label.startsWith("Compile ·")) {
                  const key = document!.uri.toString();
                  compilationStarted = true;
                  await context.workspaceState.update(
                    `fileExecution.outputValid.${key}`,
                    false,
                  );
                }
                const definition = {
                  type: "groovy-file-internal",
                  id: `${Date.now()}-${Math.random()}`,
                };
                const argumentFile = path.join(
                  output,
                  `classpath-${definition.id}.args`,
                );
                let wroteArgumentFile = false;
                try {
                  let javaSettings: Promise<string> | undefined;
                  const probeJava = () => {
                    currentProbe ??= new AbortController();
                    return (javaSettings ??= execFileAsync(
                      process.executable,
                      ["-XshowSettings:properties", "-version"],
                      {
                        cwd: process.workingDirectory,
                        env: {
                          ...globalThis.process.env,
                          ...process.environment,
                        },
                        timeout: 10000,
                        signal: currentProbe.signal,
                      },
                    ).then((result) => result.stderr + result.stdout));
                  };
                  if (!label.startsWith("Build "))
                    await qualifyWindowsArguments(
                      process.arguments,
                      globalThis.process.platform,
                      probeJava,
                    );
                  const args = label.startsWith("Build ")
                    ? process.arguments
                    : await shortenJavaArguments(
                        process.arguments,
                        argumentFile,
                        globalThis.process.platform,
                        async () => {
                          const version = (await probeJava()).match(
                            /version "(\d+)(?:\.(\d+))?/,
                          );
                          if (!version)
                            throw new Error(
                              "Could not determine the selected Java version for a long classpath.",
                            );
                          return version[1] === "1"
                            ? Number(version[2])
                            : Number(version[1]);
                        },
                        async (file, content) => {
                          await fs.writeFile(file, content, {
                            encoding: "utf8",
                            flag: "wx",
                            mode: 0o600,
                          });
                          wroteArgumentFile = true;
                        },
                      );
                  if (inputsChanged()) return false;
                  const task = new vscode.Task(
                    definition,
                    vscode.TaskScope.Workspace,
                    `${label} · ${process.workingDirectory}`,
                    "Groovy",
                    new vscode.ProcessExecution(process.executable, args, {
                      cwd: process.workingDirectory,
                      env: process.environment,
                    }),
                  );
                  task.presentationOptions = {
                    reveal: vscode.TaskRevealKind.Always,
                    clear: false,
                    echo: true,
                  };
                  const succeeded = await awaitTask(
                    vscode.tasks,
                    task,
                    (execution) => {
                      currentTask = execution;
                      if (isCancelled()) execution.terminate();
                    },
                    isCancelled,
                  );
                  currentTask = undefined;
                  if (
                    !succeeded &&
                    !isCancelled() &&
                    action === "run" &&
                    (label.startsWith("Run · Standalone ·") ||
                      label.startsWith("Compile · Standalone ·"))
                  ) {
                    void vscode.window
                      .showWarningMessage(
                        label.startsWith("Compile ·")
                          ? "The standalone compilation failed."
                          : "The standalone run failed.",
                        "Run with Project…",
                      )
                      .then((selected) => {
                        if (selected) void execute("run", document!.uri, true);
                      });
                  }
                  return succeeded;
                } finally {
                  currentProbe = undefined;
                  if (wroteArgumentFile)
                    await fs.rm(argumentFile, { force: true });
                }
              },
              cancelled: () => isCancelled() || !vscode.workspace.isTrusted,
              unavailable: (reason, alternativeCommand) => {
                if (alternativeCommand === "groovy.gradle.selectTask") {
                  void vscode.window
                    .showWarningMessage(reason, "Select Gradle Task")
                    .then((selected) => {
                      if (selected)
                        void vscode.commands.executeCommand(alternativeCommand);
                    });
                } else if (alternativeCommand === "groovy.compileFile") {
                  const source = document!.uri;
                  void vscode.window
                    .showWarningMessage(reason, "Compile File")
                    .then((selected) => {
                      if (selected)
                        void vscode.commands.executeCommand(
                          "groovy.compileFile",
                          source,
                        );
                    });
                } else void vscode.window.showWarningMessage(reason);
              },
            });
            success = !!result && action !== "compile";
            if (result && action === "compile") {
              const key = document!.uri.toString();
              const storageKey = `fileExecution.output.${key}`;
              await artifactQueue.run(key, async () => {
                const previous = context.workspaceState.get<string>(storageKey);
                let failed = false;
                let updateError: unknown;
                try {
                  await context.workspaceState.update(storageKey, output);
                  await context.workspaceState.update(
                    `fileExecution.outputValid.${key}`,
                    true,
                  );
                } catch (error) {
                  failed = true;
                  updateError = error;
                }
                keep =
                  context.workspaceState.get<string>(storageKey) === output;
                if (
                  keep &&
                  previous &&
                  previous !== output &&
                  isOwnedOutput(previous)
                ) {
                  try {
                    await fs.rm(previous, { recursive: true, force: true });
                  } catch (cleanupError) {
                    if (failed)
                      throw new AggregateError(
                        [updateError, cleanupError],
                        "Could not update compile output state or remove superseded output.",
                        { cause: cleanupError },
                      );
                    throw cleanupError;
                  }
                }
                if (failed) throw updateError;
              });
              success = true;
              void vscode.window
                .showInformationMessage(
                  `Compiled · ${result.contextLabel} · ${result.runtimeLabel}`,
                  "Reveal Output",
                )
                .then((selected) => {
                  if (selected)
                    void vscode.commands.executeCommand(
                      "revealFileInOS",
                      vscode.Uri.file(output),
                    );
                });
            }
          } catch (error) {
            operationFailed = true;
            operationError = error;
          } finally {
            try {
              if (compilationStarted && !success) {
                const key = document!.uri.toString();
                await artifactQueue.run(key, async () => {
                  await context.workspaceState.update(
                    `fileExecution.outputValid.${key}`,
                    false,
                  );
                });
              }
            } catch (invalidationError) {
              operationError = operationFailed
                ? new AggregateError(
                    [operationError, invalidationError],
                    "Compile failed and its output state could not be invalidated.",
                    { cause: invalidationError },
                  )
                : invalidationError;
              operationFailed = true;
            } finally {
              subscription.dispose();
              externalSubscription?.dispose();
              requestCancellation.dispose();
            }
          }
          if (operationFailed) throw operationError;
        },
      );
    } catch (error) {
      if (!wasCancelled)
        void vscode.window.showErrorMessage(
          `Groovy file execution failed: ${error instanceof Error ? error.message : String(error)}`,
        );
    } finally {
      if (!keep && output)
        await fs.rm(output, { recursive: true, force: true });
    }
    return success;
  }
  const provider: vscode.TaskProvider = {
    provideTasks: () => [],
    resolveTask: (task) => {
      const definition = task.definition as FileTaskDefinition;
      if (!definition.file || !["run", "compile"].includes(definition.action))
        return undefined;
      const folder =
        task.scope && typeof task.scope === "object"
          ? task.scope
          : vscode.workspace.workspaceFolders?.[0];
      const file = path.isAbsolute(definition.file)
        ? definition.file
        : path.resolve(folder?.uri.fsPath ?? "", definition.file);
      const execution = new vscode.CustomExecution(async () => {
        const closed = new vscode.EventEmitter<number>();
        const output = new vscode.EventEmitter<string>();
        const cancellation = new vscode.CancellationTokenSource();
        return {
          onDidWrite: output.event,
          onDidClose: closed.event,
          open: () => {
            output.fire(
              "Running Groovy file stages in their task terminals.\r\n",
            );
            const finish = (code: number) => {
              closed.fire(code);
              closed.dispose();
              output.dispose();
              cancellation.dispose();
            };
            void execute(
              definition.action,
              vscode.Uri.file(file),
              false,
              definition,
              cancellation.token,
            ).then(
              (ok) => finish(ok ? 0 : 1),
              () => finish(1),
            );
          },
          close: () => {
            cancellation.cancel();
          },
        };
      });
      return new vscode.Task(
        definition,
        task.scope ?? vscode.TaskScope.Workspace,
        task.name,
        "Groovy",
        execution,
        task.problemMatchers,
      );
    },
  };
  context.subscriptions.push(
    vscode.tasks.registerTaskProvider("groovy-file", provider),
  );
  context.subscriptions.push(
    vscode.commands.registerCommand("groovy.runFile", (input) =>
      execute("run", input),
    ),
    vscode.commands.registerCommand("groovy.compileFile", (input) =>
      execute("compile", input),
    ),
    vscode.commands.registerCommand("groovy.runFileWithProject", (input) =>
      execute("run", input, true),
    ),
    vscode.commands.registerCommand("groovy.runFileAgain", () => {
      const previous = last;
      return previous
        ? execute(
            "run",
            { uri: previous.uri.toString(), entryPoint: previous.entryPoint },
            false,
            previous.profile,
          )
        : execute("run");
    }),
    vscode.commands.registerCommand("groovy.revealFileOutput", async () => {
      const uri = vscode.window.activeTextEditor?.document.uri;
      const output =
        uri &&
        context.workspaceState.get<boolean>(
          `fileExecution.outputValid.${uri.toString()}`,
        ) &&
        context.workspaceState.get<string>(
          `fileExecution.output.${uri.toString()}`,
        );
      if (!output || !isOwnedOutput(output)) {
        void vscode.window.showInformationMessage(
          "No successful compile output for this file.",
        );
        return;
      }
      await vscode.commands.executeCommand(
        "revealFileInOS",
        vscode.Uri.file(output),
      );
    }),
    vscode.commands.registerCommand("groovy.configureFileExecution", () =>
      vscode.commands.executeCommand(
        "workbench.action.openSettings",
        "groovy.fileExecution",
      ),
    ),
  );
}
