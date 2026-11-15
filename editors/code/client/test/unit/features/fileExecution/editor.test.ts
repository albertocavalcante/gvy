// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import * as path from "path";
import proxyquire from "proxyquire";
import { toProtocolUri } from "../../../../src/server/uri";

describe("file execution editor integration", () => {
  function setup(
    trusted: boolean,
    save: boolean,
    prepExit = 0,
    processExit = 0,
    unsupportedPrep = false,
    cancelDuringPlan = false,
    requireScope = false,
    dirtyDuringProbe = false,
    cancelDuringProbe = false,
    projectJavaHome?: string,
    runCompiles = false,
    cancelInspect = false,
    workspaceStorage = false,
    insideWorkspace = true,
    saveAs = false,
    unavailableCommand?: string,
    clickUnavailable = false,
    overlapPublication = false,
    secondProcessFails = false,
    publicationFailure?: "before" | "after",
    javaExecutable?: string,
  ) {
    const handlers = new Map<
      string,
      (...args: unknown[]) => Promise<unknown>
    >();
    const calls: string[] = [];
    const executionSettings = new Map<string, unknown>([
      ["runtime", []],
      ["localRuntime", []],
      ["workingDirectory", ""],
    ]);
    const invokedCommands: Array<{ id: string; args: unknown[] }> = [];
    const outputPrefixes: string[] = [];
    const removedPaths: string[] = [];
    let progressCancelled = false;
    let cancelProgress = () => {};
    const requests: Array<{
      uri: string;
      action: string;
      savedScopeDirectory?: string;
      contextId?: string;
      options: {
        arguments: string[];
        jvmArguments?: string[];
        environment?: Record<string, string>;
        classpath?: string[];
        projectJavaHome?: string;
        javaExecutable?: string;
        explicitRuntime?: string[];
        localRuntime?: string[];
        workingDirectory?: string;
      };
    }> = [];
    const state = new Map<string, unknown>();
    let releaseFirstPublication!: () => void;
    let markFirstPublication!: () => void;
    const firstPublicationStarted = new Promise<void>((resolve) => {
      markFirstPublication = resolve;
    });
    const firstPublicationBlocked = new Promise<void>((resolve) => {
      releaseFirstPublication = resolve;
    });
    let markSecondProcess!: () => void;
    const secondProcessFinished = new Promise<void>((resolve) => {
      markSecondProcess = resolve;
    });
    let processCount = 0;
    let outputCount = 0;
    let provider:
      | {
          resolveTask(task: unknown): {
            execution: {
              callback(): Promise<{
                open(): void;
                onDidClose(fn: (code: number) => void): void;
              }>;
            };
          };
        }
      | undefined;
    let endTask: (event: unknown) => void = () => {};
    class ShellExecution {}
    const prepTask = {
      name: "prepare",
      definition: { type: "shell" },
      execution: unsupportedPrep ? undefined : new ShellExecution(),
    };
    class Emitter {
      private listeners: Array<(value: number | string) => void> = [];
      event = (listener: (value: number | string) => void) => {
        this.listeners.push(listener);
      };
      dispose() {}
      fire(value: number | string) {
        for (const listener of this.listeners) listener(value);
      }
    }
    let documentUri = "file:///work/a.groovy";
    let protocolPlatform: NodeJS.Platform = globalThis.process.platform;
    const uri = {
      scheme: "file",
      fsPath: "/work/a.groovy",
      toString: () => documentUri,
    };
    const document = {
      uri,
      version: 1,
      isUntitled: saveAs,
      isDirty: false,
      save: async () => {
        calls.push("save");
        if (saveAs && save)
          vscode.window.activeTextEditor.document = {
            ...document,
            isUntitled: false,
          };
        return save;
      },
    };
    const buildDocument = {
      uri: { scheme: "file", fsPath: "/work/build.gradle" },
      version: 1,
      isDirty: requireScope,
      save: async () => {
        calls.push("save-build");
        buildDocument.isDirty = false;
        return true;
      },
    };
    const process = {
      executable: "java",
      arguments: ["argument with spaces", "$(literal)"],
      workingDirectory: "/work",
      environment: {},
    };
    let pickerCount = 0;
    let planningUnavailable: string | undefined;
    const client = {
      sendRequest: async (
        _method: string,
        request: {
          uri: string;
          action: string;
          savedScopeDirectory?: string;
          contextId?: string;
          options: { arguments: string[]; outputDirectory: string };
        },
      ) => {
        requests.push(request);
        calls.push("prepare");
        if (planningUnavailable)
          return { kind: "unavailable", reason: planningUnavailable };
        if (unavailableCommand)
          return {
            kind: "unavailable",
            reason: "No runnable entry",
            alternativeCommand: unavailableCommand,
          };
        if (cancelInspect) {
          if (!request.contextId)
            return {
              kind: "choices",
              choiceType: "context",
              choices: [
                { id: "inspect-project", label: "Inspect Gradle Project…" },
              ],
            };
          if (
            request.contextId === "inspect-project" &&
            !request.savedScopeDirectory
          )
            return { kind: "save", scopeDirectory: "/work" };
          if (request.contextId === "inspect-project")
            return {
              kind: "choices",
              choiceType: "context",
              choices: [{ id: "project", label: "Project" }],
            };
        }
        if (requireScope && !request.savedScopeDirectory)
          return { kind: "save", scopeDirectory: "/work" };
        if (cancelDuringPlan) {
          progressCancelled = true;
          cancelProgress();
        }
        return {
          kind: "ready",
          contextLabel: "Standalone",
          runtimeLabel: "Groovy",
          ...(request.action === "compile"
            ? { compile: process }
            : {
                launch: process,
                ...(runCompiles ? { compile: process } : {}),
              }),
          outputDirectory: request.options.outputDirectory,
          launchToken: "launch",
        };
      },
    };
    const disposable = { dispose() {} };
    const vscode = {
      Uri: class {
        static parse() {
          return uri;
        }
        static file() {
          return uri;
        }
      },
      workspace: {
        isTrusted: trusted,
        workspaceFolders: [{ uri: { fsPath: "/work" } }],
        getWorkspaceFolder: () =>
          insideWorkspace ? { uri: { fsPath: "/work" } } : undefined,
        textDocuments: [document, buildDocument],
        openTextDocument: async () => document,
        getConfiguration: (section: string) => ({
          get: (key: string, fallback: unknown) =>
            section === "groovy" && key === "project.javaHome"
              ? projectJavaHome
              : section === "groovy.fileExecution" && key === "javaExecutable"
                ? javaExecutable
                : section === "groovy.fileExecution" &&
                    executionSettings.has(key)
                  ? executionSettings.get(key)
                  : fallback,
        }),
      },
      window: {
        activeTextEditor: { document },
        showQuickPick: async (items: { id: string }[]) => {
          pickerCount++;
          return cancelInspect && pickerCount > 1 ? undefined : items[0];
        },
        showWarningMessage: async (message: string, ...actions: string[]) => {
          calls.push(`warning:${message}`);
          return clickUnavailable ? actions[0] : undefined;
        },
        withProgress: async (
          _: unknown,
          fn: (progress: unknown, token: unknown) => Promise<void>,
        ) =>
          fn(
            {},
            {
              get isCancellationRequested() {
                return progressCancelled;
              },
              onCancellationRequested: (fn: () => void) => {
                cancelProgress = fn;
                return disposable;
              },
            },
          ),
        showErrorMessage: () => {},
        showInformationMessage: async () => undefined,
      },
      commands: {
        executeCommand: async (id: string, ...args: unknown[]) => {
          invokedCommands.push({ id, args });
        },
        registerCommand: (
          name: string,
          handler: (...args: unknown[]) => Promise<unknown>,
        ) => {
          handlers.set(name, handler);
          return disposable;
        },
      },
      tasks: {
        registerTaskProvider: (_: string, value: typeof provider) => {
          provider = value;
          return disposable;
        },
        fetchTasks: async () => [prepTask],
        onDidEndTaskProcess: (fn: (event: unknown) => void) => {
          endProcess = fn;
          return disposable;
        },
        onDidEndTask: (fn: (event: unknown) => void) => {
          endTask = fn;
          return disposable;
        },
        executeTask: async (task: unknown) => {
          calls.push("execute");
          const execution = { task, terminate() {} };
          queueMicrotask(() => {
            if (task !== prepTask && ++processCount === 2) markSecondProcess();
            if (task === prepTask) endTask({ execution });
            endProcess({
              execution,
              exitCode:
                task === prepTask
                  ? prepExit
                  : secondProcessFails && processCount === 2
                    ? 1
                    : processExit,
            });
          });
          return execution;
        },
      },
      Task: class {
        public execution: unknown;
        constructor(
          public definition: unknown,
          _scope: unknown,
          _name: unknown,
          _source: unknown,
          execution: unknown,
        ) {
          this.execution = execution;
        }
      },
      ShellExecution,
      CustomExecution: class {
        constructor(public callback: () => Promise<unknown>) {}
      },
      EventEmitter: Emitter,
      CancellationTokenSource: class {
        token = {
          isCancellationRequested: false,
          onCancellationRequested: () => disposable,
        };
        cancel() {
          calls.push("cancel-request");
        }
        dispose() {}
      },
      ProcessExecution: class {
        constructor(
          public executable: string,
          public args: string[],
        ) {
          calls.push(JSON.stringify(args));
        }
      },
      TaskScope: { Workspace: 1 },
      TaskRevealKind: { Always: 1 },
      ProgressLocation: { Notification: 1 },
      "@global": true,
    };
    let endProcess: (event: unknown) => void = () => {};
    const mod = proxyquire.noCallThru()(
      "../../../../src/features/fileExecution",
      {
        vscode,
        "../../server/client": { getClient: () => client },
        "../../server/uri": {
          toProtocolUri: (value: string) =>
            toProtocolUri(value, protocolPlatform),
        },
        "./projectJava": {
          resolveProjectJavaHome: async (
            configured: string | undefined,
            _inherited: string | undefined,
            _usable: unknown,
            probe: () => Promise<string>,
          ) => {
            if (cancelDuringProbe && requireScope) await probe();
            return configured ?? "/inherited-test-jdk";
          },
        },
        ...(dirtyDuringProbe
          ? {
              "./javaArguments": {
                qualifyWindowsArguments: async () => {},
                shortenJavaArguments: async (args: string[]) => {
                  document.isDirty = true;
                  return args;
                },
              },
            }
          : {}),
        ...(cancelDuringProbe
          ? {
              "./javaArguments": {
                qualifyWindowsArguments: async () => {},
                shortenJavaArguments: async (
                  args: string[],
                  _file: string,
                  _platform: string,
                  probe: () => Promise<number>,
                ) => {
                  await probe();
                  return args;
                },
              },
              child_process: {
                execFile: (
                  _exe: string,
                  _args: string[],
                  options: { signal: AbortSignal },
                  callback: (error: Error) => void,
                ) => {
                  calls.push("probe");
                  options.signal.addEventListener("abort", () => {
                    calls.push("abort-probe");
                    callback(new Error("aborted"));
                  });
                  queueMicrotask(() => {
                    progressCancelled = true;
                    cancelProgress();
                  });
                },
              },
            }
          : {}),
        "fs/promises": {
          mkdir: async () => {},
          mkdtemp: async (prefix: string) => {
            outputPrefixes.push(prefix);
            return overlapPublication
              ? path.join(
                  "/managed",
                  "file-execution",
                  `result-${++outputCount}`,
                )
              : "/managed/result-1";
          },
          readFile: async () => {
            throw new Error("missing");
          },
          rm: async (target: string) => {
            removedPaths.push(target);
            calls.push("cleanup");
          },
        },
      },
    );
    const context = {
      subscriptions: [],
      globalStorageUri: { fsPath: "/managed" },
      storageUri: workspaceStorage
        ? { fsPath: "/workspace-storage" }
        : undefined,
      workspaceState: {
        get: (key: string) => state.get(key),
        update: async (key: string, value: unknown) => {
          if (
            overlapPublication &&
            key.startsWith("fileExecution.output.") &&
            value === path.join("/managed", "file-execution", "result-1")
          ) {
            markFirstPublication();
            await firstPublicationBlocked;
            if (publicationFailure === "before")
              throw new Error("storage failed before update");
            if (publicationFailure === "after") {
              state.set(key, value);
              throw new Error("storage failed after update");
            }
          }
          state.set(key, value);
        },
      },
      asAbsolutePath: (s: string) => s,
    };
    mod.registerFileExecution(context, async () => true);
    return {
      handlers,
      calls,
      requests,
      executionSettings,
      setDocumentUri: (value: string, platform: NodeJS.Platform) => {
        documentUri = value;
        protocolPlatform = platform;
      },
      rejectPlanning: (reason: string) => {
        planningUnavailable = reason;
      },
      state,
      outputPrefixes,
      removedPaths,
      invokedCommands,
      firstPublicationStarted,
      secondProcessFinished,
      releaseFirstPublication,
      getProvider: () => provider,
    };
  }
  for (const separator of ["%3A", "%3a"]) {
    it(`uses the same Windows drive URI as didOpen with ${separator}`, async () => {
      const h = setup(true, true);
      h.setDocumentUri(`file:///c${separator}/work/a%3Ab.groovy`, "win32");
      assert.strictEqual(await h.handlers.get("groovy.runFile")!(), true);
      assert.ok(h.requests.length > 0);
      for (const request of h.requests)
        assert.strictEqual(request.uri, "file:///c:/work/a%3Ab.groovy");
    });
  }
  it("blocks before planning in an untrusted workspace", async () => {
    const { handlers, calls } = setup(false, true);
    await handlers.get("groovy.runFile")!();
    assert.deepStrictEqual(calls, [
      "warning:Trust this workspace to run or compile Groovy files.",
    ]);
  });
  it("stops before allocating output when save fails", async () => {
    const { handlers, calls } = setup(true, false);
    await handlers.get("groovy.runFile")!();
    assert.deepStrictEqual(calls, ["save"]);
  });
  it("passes literal arguments to ProcessExecution and cleans run output", async () => {
    const { handlers, calls } = setup(true, true);
    await handlers.get("groovy.runFile")!();
    assert.deepStrictEqual(calls, [
      "save",
      "prepare",
      "prepare",
      '["argument with spaces","$(literal)"]',
      "execute",
      "cleanup",
    ]);
  });
  it("omits implicit empty VS Code runtime and working directory defaults", async () => {
    const h = setup(true, true);
    assert.strictEqual(await h.handlers.get("groovy.runFile")!(), true);
    assert.ok(h.requests.length > 0);
    for (const request of h.requests) {
      assert.strictEqual(request.options.explicitRuntime, undefined);
      assert.strictEqual(request.options.localRuntime, undefined);
      assert.strictEqual(request.options.workingDirectory, undefined);
    }
  });
  it("preserves configured nonempty runtimes and working directory", async () => {
    const h = setup(true, true);
    h.executionSettings.set("runtime", ["/custom/groovy.jar"]);
    h.executionSettings.set("localRuntime", ["/local/groovy.jar"]);
    h.executionSettings.set("workingDirectory", "/custom/work");
    assert.strictEqual(await h.handlers.get("groovy.runFile")!(), true);
    for (const request of h.requests) {
      assert.deepStrictEqual(request.options.explicitRuntime, [
        "/custom/groovy.jar",
      ]);
      assert.deepStrictEqual(request.options.localRuntime, [
        "/local/groovy.jar",
      ]);
      assert.strictEqual(request.options.workingDirectory, "/custom/work");
    }
  });
  it("runs a groovy-file profile and honors an explicit preparation task", async () => {
    const { calls, requests, getProvider, executionSettings } = setup(
      true,
      true,
    );
    executionSettings.set("workingDirectory", "/configured/work");
    const task = getProvider()!.resolveTask({
      definition: {
        type: "groovy-file",
        file: "a.groovy",
        action: "run",
        preparationTask: "prepare",
        arguments: ["profile arg"],
        workingDirectory: "/profile/work",
      },
      name: "profile",
      scope: { uri: { fsPath: "/work" } },
      problemMatchers: [],
    });
    const terminal = await task.execution.callback();
    const closed = new Promise<number>((resolve) =>
      terminal.onDidClose(resolve),
    );
    terminal.open();
    assert.strictEqual(await closed, 0);
    assert.deepStrictEqual(requests[0].options.arguments, ["profile arg"]);
    assert.ok(
      requests.every(
        (request) => request.options.workingDirectory === "/profile/work",
      ),
    );
    assert.deepStrictEqual(calls, [
      "save",
      "execute",
      "prepare",
      "prepare",
      '["argument with spaces","$(literal)"]',
      "execute",
      "cleanup",
    ]);
  });
  it("reports profile preparation failure and does not request a plan", async () => {
    const { calls, getProvider } = setup(true, true, 2);
    const task = getProvider()!.resolveTask({
      definition: {
        type: "groovy-file",
        file: "a.groovy",
        action: "run",
        preparationTask: "prepare",
      },
      name: "profile",
      scope: { uri: { fsPath: "/work" } },
      problemMatchers: [],
    });
    const terminal = await task.execution.callback();
    const closed = new Promise<number>((resolve) =>
      terminal.onDidClose(resolve),
    );
    terminal.open();
    assert.strictEqual(await closed, 1);
    assert.deepStrictEqual(calls, ["save", "execute", "cleanup"]);
  });
  it("does not present the previous artifact as current after compile failure", async () => {
    const { handlers, state } = setup(true, true, 0, 1);
    state.set(
      "fileExecution.output.file:///work/a.groovy",
      "/managed/file-execution/result-old",
    );
    state.set("fileExecution.outputValid.file:///work/a.groovy", true);
    await handlers.get("groovy.compileFile")!();
    assert.strictEqual(
      state.get("fileExecution.outputValid.file:///work/a.groovy"),
      false,
    );
    assert.strictEqual(
      state.get("fileExecution.output.file:///work/a.groovy"),
      "/managed/file-execution/result-old",
    );
  });
  for (const reason of [
    "Saved source has a syntax error",
    "Planning unavailable",
  ]) {
    it(`invalidates previous compile output before planning rejects: ${reason}`, async () => {
      const h = setup(true, true);
      const outputKey = "fileExecution.output.file:///work/a.groovy";
      const validKey = "fileExecution.outputValid.file:///work/a.groovy";
      h.state.set(outputKey, "/external/previous-output");
      h.state.set(validKey, true);
      h.rejectPlanning(reason);
      assert.strictEqual(await h.handlers.get("groovy.compileFile")!(), false);
      assert.strictEqual(h.state.get(validKey), false);
      assert.strictEqual(h.state.get(outputKey), "/external/previous-output");
      assert.ok(!h.calls.includes("execute"));
      assert.ok(!h.removedPaths.includes("/external/previous-output"));
    });
  }
  it("preserves previous compile validity when saving is cancelled", async () => {
    const h = setup(true, false);
    const validKey = "fileExecution.outputValid.file:///work/a.groovy";
    h.state.set(validKey, true);
    assert.strictEqual(await h.handlers.get("groovy.compileFile")!(), false);
    assert.strictEqual(h.state.get(validKey), true);
    assert.strictEqual(h.requests.length, 0);
  });
  it("rejects unsupported compound preparation before executing it", async () => {
    const { calls, getProvider } = setup(true, true, 0, 0, true);
    const task = getProvider()!.resolveTask({
      definition: {
        type: "groovy-file",
        file: "a.groovy",
        action: "run",
        preparationTask: "prepare",
      },
      name: "profile",
      scope: { uri: { fsPath: "/work" } },
      problemMatchers: [],
    });
    const terminal = await task.execution.callback();
    const closed = new Promise<number>((resolve) =>
      terminal.onDidClose(resolve),
    );
    terminal.open();
    assert.strictEqual(await closed, 1);
    assert.deepStrictEqual(calls, ["save", "cleanup"]);
  });
  it("cancels the LSP request and does not launch when progress is cancelled", async () => {
    const { handlers, calls } = setup(true, true, 0, 0, false, true);
    await handlers.get("groovy.runFile")!();
    assert.deepStrictEqual(calls, [
      "save",
      "prepare",
      "cancel-request",
      "cleanup",
    ]);
  });
  it("offers project context after a failed standalone run", async () => {
    const { handlers, calls } = setup(true, true, 0, 1);
    await handlers.get("groovy.runFile")!();
    assert.ok(calls.includes("warning:The standalone run failed."));
    assert.strictEqual(calls.filter((call) => call === "execute").length, 1);
  });
  it("saves dirty build configuration before acknowledging model evaluation", async () => {
    const { handlers, calls, requests } = setup(
      true,
      true,
      0,
      0,
      false,
      false,
      true,
    );
    await handlers.get("groovy.runFile")!();
    assert.strictEqual(requests[0].savedScopeDirectory, undefined);
    assert.strictEqual(requests[1].savedScopeDirectory, "/work");
    assert.strictEqual(
      requests[1].options.projectJavaHome,
      "/inherited-test-jdk",
    );
    assert.ok(calls.indexOf("save-build") < calls.lastIndexOf("prepare"));
  });
  it("does not start a process if the document changes during the Java probe", async () => {
    const { handlers, calls } = setup(
      true,
      true,
      0,
      0,
      false,
      false,
      false,
      true,
    );
    await handlers.get("groovy.runFile")!();
    assert.ok(!calls.includes("execute"));
  });
  it("aborts a running Java probe when execution is cancelled", async () => {
    const { handlers, calls } = setup(
      true,
      true,
      0,
      0,
      false,
      false,
      false,
      false,
      true,
    );
    await handlers.get("groovy.runFile")!();
    assert.ok(calls.includes("abort-probe"));
    assert.ok(!calls.includes("execute"));
  });
  it("passes the selected project JDK separately from the language server", async () => {
    const { handlers, requests } = setup(
      true,
      true,
      0,
      0,
      false,
      false,
      false,
      false,
      false,
      "/project jdk",
    );
    await handlers.get("groovy.runFile")!();
    assert.strictEqual(requests[0].options.projectJavaHome, "/project jdk");
    assert.strictEqual(
      requests[0].options.javaExecutable,
      path.join(
        "/project jdk",
        "bin",
        process.platform === "win32" ? "java.exe" : "java",
      ),
    );
  });
  for (const override of [undefined, "/explicit/java"]) {
    it(`uses the resolved inherited project JDK with explicit override ${override ?? "unset"}`, async () => {
      const previousHome = process.env.JAVA_HOME;
      const previousPath = process.env.PATH;
      process.env.JAVA_HOME = "/inherited-test-jdk";
      process.env.PATH = "/different-java17/bin";
      try {
        const h = setup(
          true,
          true,
          0,
          0,
          false,
          false,
          true,
          false,
          false,
          undefined,
          false,
          false,
          false,
          true,
          false,
          undefined,
          false,
          false,
          false,
          undefined,
          override,
        );
        assert.strictEqual(await h.handlers.get("groovy.runFile")!(), true);
        const scoped = h.requests.filter(
          (request) => request.savedScopeDirectory,
        );
        assert.ok(scoped.length > 0);
        for (const request of scoped) {
          assert.strictEqual(
            request.options.projectJavaHome,
            "/inherited-test-jdk",
          );
          assert.strictEqual(
            request.options.javaExecutable,
            override ??
              path.join(
                "/inherited-test-jdk",
                "bin",
                process.platform === "win32" ? "java.exe" : "java",
              ),
          );
        }
      } finally {
        if (previousHome === undefined) delete process.env.JAVA_HOME;
        else process.env.JAVA_HOME = previousHome;
        if (previousPath === undefined) delete process.env.PATH;
        else process.env.PATH = previousPath;
      }
    });
  }
  it("aborts project Java discovery before model evaluation on cancellation", async () => {
    const { handlers, calls } = setup(
      true,
      true,
      0,
      0,
      false,
      false,
      true,
      false,
      true,
    );
    await handlers.get("groovy.runFile")!();
    assert.ok(calls.includes("abort-probe"));
    assert.strictEqual(calls.filter((call) => call === "prepare").length, 1);
    assert.ok(!calls.includes("execute"));
  });
  it("offers project context when a standalone run fails to compile", async () => {
    const { handlers, calls } = setup(
      true,
      true,
      0,
      1,
      false,
      false,
      false,
      false,
      false,
      undefined,
      true,
    );
    await handlers.get("groovy.runFile")!();
    assert.ok(calls.includes("warning:The standalone compilation failed."));
    assert.strictEqual(calls.filter((call) => call === "execute").length, 1);
  });
  it("does not remember the transient inspect-project choice if the picker is cancelled", async () => {
    const { handlers, calls, state } = setup(
      true,
      true,
      0,
      0,
      false,
      false,
      false,
      false,
      false,
      undefined,
      false,
      true,
    );
    await handlers.get("groovy.runFile")!();
    assert.strictEqual(
      state.get("fileExecution.choice.file:///work/a.groovy"),
      undefined,
    );
    assert.ok(!calls.includes("execute"));
  });
  it("uses workspace storage for workspace files and global storage outside the workspace", async () => {
    const inWorkspace = setup(
      true,
      true,
      0,
      0,
      false,
      false,
      false,
      false,
      false,
      undefined,
      false,
      false,
      true,
      true,
    );
    await inWorkspace.handlers.get("groovy.runFile")!();
    assert.strictEqual(
      inWorkspace.outputPrefixes[0],
      path.join("/workspace-storage", "file-execution", "result-"),
    );
    const outside = setup(
      true,
      true,
      0,
      0,
      false,
      false,
      false,
      false,
      false,
      undefined,
      false,
      false,
      true,
      false,
    );
    await outside.handlers.get("groovy.runFile")!();
    assert.strictEqual(
      outside.outputPrefixes[0],
      path.join("/managed", "file-execution", "result-"),
    );
  });
  it("never deletes a stored previous output outside managed storage", async () => {
    const { handlers, state, removedPaths } = setup(true, true);
    state.set(
      "fileExecution.output.file:///work/a.groovy",
      "/unowned/result-do-not-delete",
    );
    await handlers.get("groovy.compileFile")!();
    assert.ok(!removedPaths.includes("/unowned/result-do-not-delete"));
  });
  it("selects workspace output storage after an untitled Save As", async () => {
    const { handlers, outputPrefixes } = setup(
      true,
      true,
      0,
      0,
      false,
      false,
      false,
      false,
      false,
      undefined,
      false,
      false,
      true,
      true,
      true,
    );
    await handlers.get("groovy.runFile")!();
    assert.strictEqual(
      outputPrefixes[0],
      path.join("/workspace-storage", "file-execution", "result-"),
    );
  });
  it("Run Again repeats the last task profile even after Compile File", async () => {
    const { handlers, requests, getProvider, calls } = setup(true, true);
    const definition = {
      type: "groovy-file",
      file: "a.groovy",
      action: "run",
      preparationTask: "prepare",
      arguments: ["profile arg"],
      jvmArguments: ["-Xmx128m"],
      environment: { PROFILE: "value" },
      classpath: ["/dependency.jar"],
    };
    const task = getProvider()!.resolveTask({
      definition,
      name: "profile",
      scope: { uri: { fsPath: "/work" } },
      problemMatchers: [],
    });
    const terminal = await task.execution.callback();
    const closed = new Promise<number>((resolve) =>
      terminal.onDidClose(resolve),
    );
    terminal.open();
    assert.strictEqual(await closed, 0);
    await handlers.get("groovy.compileFile")!();
    await handlers.get("groovy.runFileAgain")!();
    const last = requests[requests.length - 1].options;
    assert.deepStrictEqual(last.arguments, ["profile arg"]);
    assert.deepStrictEqual(last.jvmArguments, ["-Xmx128m"]);
    assert.deepStrictEqual(last.environment, { PROFILE: "value" });
    assert.deepStrictEqual(last.classpath, ["/dependency.jar"]);
    assert.strictEqual(calls.filter((call) => call === "execute").length, 5);
  });
  it("offers Compile File for the original document only after the user selects it", async () => {
    const ignored = setup(
      true,
      true,
      0,
      0,
      false,
      false,
      false,
      false,
      false,
      undefined,
      false,
      false,
      false,
      true,
      false,
      "groovy.compileFile",
      false,
    );
    await ignored.handlers.get("groovy.runFile")!();
    assert.deepStrictEqual(ignored.invokedCommands, []);
    const clicked = setup(
      true,
      true,
      0,
      0,
      false,
      false,
      false,
      false,
      false,
      undefined,
      false,
      false,
      false,
      true,
      false,
      "groovy.compileFile",
      true,
    );
    await clicked.handlers.get("groovy.runFile")!();
    assert.strictEqual(clicked.invokedCommands.length, 1);
    assert.strictEqual(clicked.invokedCommands[0].id, "groovy.compileFile");
    assert.strictEqual(
      (clicked.invokedCommands[0].args[0] as { fsPath: string }).fsPath,
      "/work/a.groovy",
    );
    const unknown = setup(
      true,
      true,
      0,
      0,
      false,
      false,
      false,
      false,
      false,
      undefined,
      false,
      false,
      false,
      true,
      false,
      "arbitrary.command",
      true,
    );
    await unknown.handlers.get("groovy.runFile")!();
    assert.deepStrictEqual(unknown.invokedCommands, []);
  });
  it("publishes overlapping successful compiles in completion order without orphaning output", async () => {
    const h = setup(
      true,
      true,
      0,
      0,
      false,
      false,
      false,
      false,
      false,
      undefined,
      false,
      false,
      false,
      true,
      false,
      undefined,
      false,
      true,
    );
    const key = "fileExecution.output.file:///work/a.groovy";
    h.state.set(key, path.join("/managed", "file-execution", "result-old"));
    const first = h.handlers.get("groovy.compileFile")!();
    await h.firstPublicationStarted;
    const second = h.handlers.get("groovy.compileFile")!();
    await new Promise<void>((resolve) => setImmediate(resolve));
    assert.strictEqual(
      h.state.get(key),
      path.join("/managed", "file-execution", "result-old"),
    );
    h.releaseFirstPublication();
    await Promise.all([first, second]);
    assert.strictEqual(
      h.state.get(key),
      path.join("/managed", "file-execution", "result-2"),
    );
    assert.deepStrictEqual(h.removedPaths, [
      path.join("/managed", "file-execution", "result-old"),
      path.join("/managed", "file-execution", "result-1"),
    ]);
  });
  it("does not present output as current when an overlapping compile fails last", async () => {
    const h = setup(
      true,
      true,
      0,
      0,
      false,
      false,
      false,
      false,
      false,
      undefined,
      false,
      false,
      false,
      true,
      false,
      undefined,
      false,
      true,
      true,
    );
    const key = "fileExecution.output.file:///work/a.groovy";
    const validKey = "fileExecution.outputValid.file:///work/a.groovy";
    h.state.set(key, path.join("/managed", "file-execution", "result-old"));
    const first = h.handlers.get("groovy.compileFile")!();
    await h.firstPublicationStarted;
    const second = h.handlers.get("groovy.compileFile")!();
    await new Promise<void>((resolve) => setImmediate(resolve));
    h.releaseFirstPublication();
    assert.deepStrictEqual(await Promise.all([first, second]), [true, false]);
    assert.strictEqual(
      h.state.get(key),
      path.join("/managed", "file-execution", "result-1"),
    );
    assert.strictEqual(h.state.get(validKey), false);
    assert.deepStrictEqual(h.removedPaths, [
      path.join("/managed", "file-execution", "result-old"),
      path.join("/managed", "file-execution", "result-2"),
    ]);
  });
  for (const failure of ["before", "after"] as const) {
    it(`preserves owned output and advances the queue when storage fails ${failure} mutation`, async () => {
      const h = setup(
        true,
        true,
        0,
        0,
        false,
        false,
        false,
        false,
        false,
        undefined,
        false,
        false,
        false,
        true,
        false,
        undefined,
        false,
        true,
        false,
        failure,
      );
      const key = "fileExecution.output.file:///work/a.groovy";
      const validKey = "fileExecution.outputValid.file:///work/a.groovy";
      const old = path.join("/managed", "file-execution", "result-old");
      const firstOutput = path.join("/managed", "file-execution", "result-1");
      const secondOutput = path.join("/managed", "file-execution", "result-2");
      h.state.set(key, old);
      const first = h.handlers.get("groovy.compileFile")!();
      await h.firstPublicationStarted;
      h.releaseFirstPublication();
      assert.strictEqual(await first, false);
      assert.strictEqual(
        h.state.get(key),
        failure === "after" ? firstOutput : old,
      );
      assert.strictEqual(h.state.get(validKey), false);
      assert.deepStrictEqual(h.removedPaths, [
        failure === "after" ? old : firstOutput,
      ]);
      assert.strictEqual(await h.handlers.get("groovy.compileFile")!(), true);
      assert.strictEqual(h.state.get(key), secondOutput);
      assert.strictEqual(h.state.get(validKey), true);
      assert.deepStrictEqual(h.removedPaths, [
        failure === "after" ? old : firstOutput,
        failure === "after" ? firstOutput : old,
      ]);
    });
  }
});
