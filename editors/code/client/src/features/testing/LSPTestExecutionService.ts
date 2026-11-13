// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as vscode from "vscode";
import * as cp from "child_process";
import * as path from "path";
import * as fs from "fs";
import { createBoundedLines, pipeBoundedLines } from "../../utils/boundedLines";
import { ITestExecutionService } from "./ITestExecutionService";
import {
  planRunItems,
  TestSelectionDescriptor,
  TestTarget,
} from "./testTarget";
import {
  buildProcessInvocation,
  buildToolOf,
  killProcessTree,
  ProcessInvocation,
  withJavaHome,
} from "./processInvocation";
import { TestService, TestCommand, TestResultItem } from "./TestService";
import { TestEventConsumer } from "./TestEventConsumer";
import { CoverageService } from "./CoverageService";

interface TestRunOptions {
  withCoverage?: boolean;
  coverageService?: CoverageService;
}

// Note: kept at module scope for potential reuse by other test-related utilities
// in this module. If it remains used only by LSPTestExecutionService long-term,
// it could be moved into the class as a private static helper.
function normalizeTestId(id: string): string {
  return (
    id
      // Replace one or more invalid characters with a single underscore
      .replace(/[^\w.]+/g, "_")
      // Remove leading or trailing underscores
      .replace(/^_+|_+$/g, "")
  );
}

/** Detect fixed ASCII Maven messages with bounded state, even in discarded bytes. */
function createHttpBlockerMatcher(
  found: () => void,
): (chunk: Buffer | string) => void {
  const patterns = [
    "maven-default-http-blocker",
    "blocked mirror for repositories",
  ].map((text) => Buffer.from(text));
  const tables = patterns.map((pattern) => {
    const prefix = new Array<number>(pattern.length).fill(0);
    for (let i = 1, j = 0; i < pattern.length; i++) {
      while (j > 0 && pattern[i] !== pattern[j]) j = prefix[j - 1];
      if (pattern[i] === pattern[j]) j++;
      prefix[i] = j;
    }
    return prefix;
  });
  const states = patterns.map(() => 0);
  return (chunk) => {
    if (!Buffer.isBuffer(chunk)) return;
    for (const value of chunk) {
      const byte = value >= 65 && value <= 90 ? value + 32 : value;
      for (let i = 0; i < patterns.length; i++) {
        const pattern = patterns[i];
        const prefix = tables[i];
        let j = states[i];
        if (byte === 10 || byte === 13) {
          states[i] = 0;
          continue;
        }
        while (j > 0 && byte !== pattern[j]) j = prefix[j - 1];
        if (byte === pattern[j]) j++;
        if (j === pattern.length) {
          found();
          j = prefix[j - 1];
        }
        states[i] = j;
      }
    }
  };
}

/** The build command for a test item cannot be spawned safely. */
class UnrunnableCommandError extends Error {}

export class LSPTestExecutionService implements ITestExecutionService {
  private readonly initScriptPath: string;

  constructor(
    private readonly testService: TestService,
    private readonly logger: vscode.OutputChannel,
    extensionPath: string,
    private readonly describeItem: (
      item: vscode.TestItem,
    ) => TestSelectionDescriptor<vscode.TestItem>,
  ) {
    this.initScriptPath = path.join(
      extensionPath,
      "resources",
      "test-events.init.gradle",
    );
  }

  async runTests(
    request: vscode.TestRunRequest,
    token: vscode.CancellationToken,
    testController: vscode.TestController,
  ): Promise<void> {
    return this.runTestsInternal(request, token, testController, {});
  }

  async runTestsWithCoverage(
    request: vscode.TestRunRequest,
    token: vscode.CancellationToken,
    testController: vscode.TestController,
    coverageService: CoverageService,
  ): Promise<void> {
    return this.runTestsInternal(request, token, testController, {
      withCoverage: true,
      coverageService,
    });
  }

  private async runTestsInternal(
    request: vscode.TestRunRequest,
    token: vscode.CancellationToken,
    testController: vscode.TestController,
    options: TestRunOptions = {},
  ): Promise<void> {
    const run = testController.createTestRun(request);
    // Runs started from commands or CodeLens pass a token nobody cancels;
    // the run's own token is what Test Explorer's Stop button cancels.
    const tokens = [token, run.token];
    const isCancelled = () => tokens.some((t) => t.isCancellationRequested);
    let consumer: TestEventConsumer | undefined;
    const cancellationSubscriptions: vscode.Disposable[] = [];
    try {
      if (isCancelled()) return;
      const plan = planRunItems(
        request.include,
        request.exclude,
        testController.items,
        this.describeItem,
      );
      if (plan.kind === "unsupported") {
        this.reportPlanningFailure(
          run,
          plan.reason === "incomplete-suite"
            ? "Cannot run this selection: discovery has not established all suite children. Refresh tests and try again."
            : "Cannot run this selection exactly. Select whole discovered tests or suites; individual runtime iterations are not supported.",
        );
        return;
      }
      if (plan.selections.length === 0) return;
      // Validate the entire selection before requesting any command. IDs are
      // opaque UI identity; targets were bound from discovery metadata.
      const targets: {
        item: vscode.TestItem;
        target: Readonly<TestTarget>;
        uri: string;
      }[] = [];
      for (const { item, target } of plan.selections) {
        const uri = item.uri?.toString();
        if (!uri) {
          this.reportPlanningFailure(
            run,
            "Cannot run this selection: a test has no source URI. Refresh tests and try again.",
          );
          return;
        }
        targets.push({ item, target, uri });
      }

      const prepared: {
        item: vscode.TestItem;
        command: TestCommand;
        invocation: ProcessInvocation;
      }[] = [];
      // Stop must release the UI run even when command generation does not
      // settle. Promise.race also observes a losing lookup's later rejection.
      const lookupCancelled = Symbol("cancelled test command lookup");
      const cancelled = new Promise<typeof lookupCancelled>((resolve) => {
        for (const cancellationToken of tokens) {
          cancellationSubscriptions.push(
            cancellationToken.onCancellationRequested(() =>
              resolve(lookupCancelled),
            ),
          );
        }
      });
      for (const { item, target, uri } of targets) {
        if (isCancelled()) return;
        const command = await Promise.race([
          this.testService.getTestCommand(uri, target.suite, target.test),
          cancelled,
        ]);
        if (command === lookupCancelled || isCancelled()) return;
        if (!command) {
          const message =
            "No test execution command was returned. The entire selection was cancelled before starting any tests.";
          run.errored(item, new vscode.TestMessage(message));
          this.reportPlanningFailure(run, message);
          return;
        }
        const args = [...command.args];
        if (options.withCoverage) {
          const tool = buildToolOf(command.executable);
          if (tool === "gradle") args.push("jacocoTestReport");
          else if (tool === "maven") args.push("jacoco:report");
        }
        const executableCommand = { ...command, args };
        try {
          prepared.push({
            item,
            command: executableCommand,
            invocation: this.prepareInvocation(executableCommand),
          });
        } catch (error) {
          if (!(error instanceof UnrunnableCommandError)) throw error;
          run.errored(item, new vscode.TestMessage(error.message));
          this.reportPlanningFailure(run, error.message);
          return;
        }
      }
      if (isCancelled()) return;
      const testsToRun = prepared.map(({ item }) => item);
      consumer = new TestEventConsumer(run, this.logger, testController);
      for (const item of this.collectAllTestItems(testsToRun)) {
        consumer.registerTestItem(item.id, item);
      }
      for (const item of testsToRun) run.enqueued(item);

      // Get workspace URI for LSP requests
      const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
      const workspaceUri = workspaceFolder?.uri.toString() || "";

      const healthyMavenItems: vscode.TestItem[] = [];
      const affectedByLoss = new Set<vscode.TestItem>();

      for (const { item, command, invocation } of prepared) {
        if (isCancelled()) break;
        const isMaven = buildToolOf(command.executable) === "maven";

        const outcome = await this.executeCommand(
          command,
          invocation,
          consumer,
          tokens,
        );
        if (outcome.incomplete) {
          for (const affected of this.collectAllTestItems([item]))
            affectedByLoss.add(affected);
        }
        // Reports lack current-run provenance; do not apply them to failed or incomplete executions.
        if (isMaven && outcome.spawned && !outcome.incomplete) {
          healthyMavenItems.push(item);
        }
      }

      const excluded = new Set(this.collectAllTestItems(request.exclude ?? []));

      // For Maven, fetch and apply Surefire results from LSP (once after all tests complete)
      if (healthyMavenItems.length > 0 && !isCancelled()) {
        await this.applyTestResults(
          workspaceUri,
          run,
          healthyMavenItems,
          consumer,
          affectedByLoss,
          excluded,
        );
      }

      if (!isCancelled()) {
        const selected = new Set(testsToRun);
        for (const item of affectedByLoss) {
          if (
            !consumer.isTerminal(item) &&
            !excluded.has(item) &&
            (selected.has(item) || consumer.wasStarted(item))
          ) {
            consumer.markTerminal(item);
            run.errored(
              item,
              new vscode.TestMessage(
                "Test execution or output was incomplete; the result could not be determined.",
              ),
            );
          }
        }
      }

      // After all tests complete, fetch and add coverage if requested
      if (options.withCoverage && options.coverageService && !isCancelled()) {
        await options.coverageService.addCoverageToRun(run, workspaceUri);
      }
    } catch (error) {
      this.logger.appendLine(`Test execution error: ${error}`);
      run.appendOutput(`Test execution error: ${error}\r\n`);
    } finally {
      try {
        consumer?.clear();
      } finally {
        try {
          for (const subscription of cancellationSubscriptions)
            subscription.dispose();
        } finally {
          run.end();
        }
      }
    }
  }

  private reportPlanningFailure(run: vscode.TestRun, message: string): void {
    this.logger.appendLine(`[Testing] ${message}`);
    run.appendOutput(`${message}\r\n`);
    // This non-modal notification must not keep the TestRun open until dismissal.
    void vscode.window
      .showWarningMessage(message)
      .then(undefined, (error: unknown) => {
        this.logger.appendLine(
          `Could not show test selection warning: ${error}`,
        );
      });
  }

  /**
   * Collect all test items recursively from a list of items.
   * Includes cycle detection to prevent stack overflow from circular references.
   */
  private collectAllTestItems(
    items: readonly vscode.TestItem[],
  ): vscode.TestItem[] {
    const result: vscode.TestItem[] = [];
    const visited = new Set<vscode.TestItem>();

    const collect = (item: vscode.TestItem) => {
      if (visited.has(item)) {
        // Protect against potential cycles in the test item graph
        return;
      }
      visited.add(item);
      result.push(item);
      item.children.forEach((child) => collect(child));
    };
    items.forEach(collect);
    return result;
  }

  /**
   * Fetch test results from LSP (Surefire XML parsing) and apply to TestRun.
   */
  private async applyTestResults(
    workspaceUri: string,
    run: vscode.TestRun,
    testsToRun: readonly vscode.TestItem[],
    consumer: TestEventConsumer,
    affectedByLoss: ReadonlySet<vscode.TestItem>,
    excluded: ReadonlySet<vscode.TestItem>,
  ): Promise<void> {
    try {
      const results = await this.testService.getTestResults(workspaceUri);
      if (results.results.length === 0) {
        this.logger.appendLine(
          "[LSP] No test results found in Surefire reports",
        );
        return;
      }

      this.logger.appendLine(
        `[LSP] Retrieved ${results.results.length} test results from Surefire XML`,
      );

      // Collect all test items (including children) for matching
      const allTestItems = this.collectAllTestItems(testsToRun);

      // Build a map with multiple lookup keys for robust matching
      const resultMap = new Map<string, TestResultItem>();
      for (const result of results.results) {
        // Add exact testId
        if (resultMap.has(result.testId)) {
          this.logger.appendLine(
            `[WARN] Collision detected for testId "${result.testId}". Later result will overwrite earlier one.`,
          );
        }
        resultMap.set(result.testId, result);

        // Add className.name combination
        if (result.className) {
          const classNameKey = `${result.className}.${result.name}`;
          if (resultMap.has(classNameKey)) {
            this.logger.appendLine(
              `[WARN] Collision detected for className.name "${classNameKey}". Later result will overwrite earlier one.`,
            );
          }
          resultMap.set(classNameKey, result);
        }

        // Add normalized testId (spaces replaced with underscores)
        const normalized = normalizeTestId(result.testId);
        if (resultMap.has(normalized)) {
          this.logger.appendLine(
            `[WARN] Collision detected for normalized ID "${normalized}". Later result will overwrite earlier one.`,
          );
        }
        resultMap.set(normalized, result);

        // Add just the test name for loose matching
        if (resultMap.has(result.name)) {
          this.logger.appendLine(
            `[WARN] Collision detected for test name "${result.name}". Later result will overwrite earlier one. Consider using more specific matching keys.`,
          );
        }
        resultMap.set(result.name, result);
      }

      // Apply results to test items using smart matching
      for (const item of allTestItems) {
        // Try multiple matching strategies
        let result = resultMap.get(item.id);

        // If not found, try normalized ID
        if (!result) {
          result = resultMap.get(normalizeTestId(item.id));
        }

        // If not found, try matching by label/name
        if (!result) {
          result = resultMap.get(item.label);
        }

        // If not found, try extracting just the method name from ID
        if (!result && item.id.includes(".")) {
          const methodName = item.id.substring(item.id.lastIndexOf(".") + 1);
          result = resultMap.get(methodName);
        }

        if (
          result &&
          !excluded.has(item) &&
          !affectedByLoss.has(item) &&
          !consumer.isTerminal(item)
        ) {
          this.applyResultToItem(run, item, result);
          consumer.markTerminal(item);
        }
      }
    } catch (error) {
      this.logger.appendLine(`[LSP] Error fetching test results: ${error}`);
    }
  }

  /**
   * Apply a single test result to a test item.
   */
  private applyResultToItem(
    run: vscode.TestRun,
    item: vscode.TestItem,
    result: TestResultItem,
  ): void {
    // Append output if available (CRLF required for VS Code Test Results panel)
    if (result.output) {
      const formattedOutput = result.output.replace(/\r?\n/g, "\r\n");
      run.appendOutput(`--- Output for ${result.name} ---\r\n`);
      run.appendOutput(formattedOutput + "\r\n", undefined, item);
    }

    // Report status
    switch (result.status) {
      case "SUCCESS":
        run.passed(item, result.durationMs);
        break;
      case "FAILURE":
        {
          const message = new vscode.TestMessage(
            result.failureMessage || "Test failed",
          );
          if (result.stackTrace) {
            message.message = `${result.failureMessage || "Test failed"}\n\n${result.stackTrace}`;
          }
          run.failed(item, message, result.durationMs);
        }
        break;
      case "SKIPPED":
        run.skipped(item);
        break;
      case "ERROR":
        {
          const errorMessage = new vscode.TestMessage(
            result.failureMessage || "Test error",
          );
          if (result.stackTrace) {
            errorMessage.message = `${result.failureMessage || "Test error"}\n\n${result.stackTrace}`;
          }
          run.errored(item, errorMessage, result.durationMs);
        }
        break;
    }
  }

  /**
   * Recursively apply results to children of a test item.
   */
  private applyResultsToChildren(
    run: vscode.TestRun,
    item: vscode.TestItem,
    resultMap: Map<string, TestResultItem>,
  ): void {
    item.children.forEach((child) => {
      const result = resultMap.get(child.id);
      if (result) {
        this.applyResultToItem(run, child, result);
      } else {
        // Recurse into children
        this.applyResultsToChildren(run, child, resultMap);
      }
    });
  }

  async debugTests(
    _request: vscode.TestRunRequest,
    _token: vscode.CancellationToken,
  ): Promise<void> {
    // TODO: Implement debug using specific LSP command or debug adapter
    this.logger.appendLine("Debug not implemented in LSP service yet.");
    vscode.window.showWarningMessage(
      "Debug support is not yet implemented in the Language Server.",
    );
  }

  private getProjectJavaHome(): string | undefined {
    const config = vscode.workspace.getConfiguration("groovy");
    return config.get<string>("project.javaHome");
  }

  private isValidJavaHome(javaHome: string): boolean {
    try {
      // Must be absolute path
      if (!path.isAbsolute(javaHome)) {
        this.logger.appendLine(
          `[Security] Invalid JAVA_HOME: path must be absolute (${javaHome})`,
        );
        return false;
      }

      // Normalize and resolve symlinks in JAVA_HOME
      const normalizedJavaHome = path.normalize(javaHome);
      const realJavaHome = fs.realpathSync(normalizedJavaHome);
      const javaHomeStat = fs.statSync(realJavaHome);
      if (!javaHomeStat.isDirectory()) {
        this.logger.appendLine(
          `[Security] Invalid JAVA_HOME: not a directory (${realJavaHome})`,
        );
        return false;
      }

      // Must exist and contain bin/java (or bin/java.exe on Windows)
      const javaExecutable = process.platform === "win32" ? "java.exe" : "java";
      const javaPath = path.join(realJavaHome, "bin", javaExecutable);

      // Resolve symlinks for the java executable
      const realJavaPath = fs.realpathSync(javaPath);

      // Ensure the resolved java executable is within the resolved JAVA_HOME directory
      const relativeToHome = path.relative(realJavaHome, realJavaPath);
      if (relativeToHome.startsWith("..") || path.isAbsolute(relativeToHome)) {
        this.logger.appendLine(
          `[Security] Invalid JAVA_HOME: java executable resolves outside JAVA_HOME (${realJavaPath})`,
        );
        return false;
      }

      const javaPathStat = fs.statSync(realJavaPath);
      if (!javaPathStat.isFile()) {
        this.logger.appendLine(
          `[Security] Invalid JAVA_HOME: java executable is not a file (${realJavaPath})`,
        );
        return false;
      }

      return true;
    } catch (err) {
      this.logger.appendLine(
        `[Security] Invalid JAVA_HOME: error while validating (${javaHome}): ${
          err instanceof Error ? err.message : String(err)
        }`,
      );
      return false;
    }
  }

  private prepareInvocation(cmd: TestCommand): ProcessInvocation {
    const { executable, args } = cmd;
    const tool = buildToolOf(executable);
    const isGradle = tool === "gradle";
    const isMaven = tool === "maven";
    let finalArgs = [...args];

    // Inject Gradle Init Script if it's Gradle
    if (isGradle) {
      // Find where to insert --init-script. Usually at start or before 'test' task.
      // LSP likely returns ['test', '--tests', ...]
      // We prepend init script args
      finalArgs = [
        "--init-script",
        this.initScriptPath,
        ...finalArgs,
        "--console=plain", // Ensure parsing works
      ];
    } else if (isMaven) {
      // Ensure quiet/plain output for parsing?
      // Maven output is harder to force to "plain" without affecting build.
      // We'll rely on the default text output.
      // Maybe add '-q' if not present?
      if (!finalArgs.includes("-q") && !finalArgs.includes("--quiet")) {
        finalArgs.push("-q");
      }
    }

    try {
      return buildProcessInvocation(executable, finalArgs);
    } catch (error) {
      throw new UnrunnableCommandError(
        `This test cannot be run through the Windows command shell, which ` +
          `${executable} needs. ${error instanceof Error ? error.message : String(error)}. If that is ` +
          `the test name, rename the test or run its enclosing class; if it ` +
          `is a path, move the project to a path without those characters.`,
      );
    }
  }

  private async executeCommand(
    cmd: TestCommand,
    invocation: ProcessInvocation,
    consumer: TestEventConsumer,
    tokens: readonly vscode.CancellationToken[],
  ): Promise<{ spawned: boolean; incomplete: boolean }> {
    const { executable, args, cwd, env } = cmd;
    this.logger.appendLine(
      `[LSP] Executing: ${executable} ${args.join(" ")} (in ${cwd})`,
    );

    // Get project build JDK from settings
    const projectJavaHome = this.getProjectJavaHome();

    // Build environment with explicit JAVA_HOME if configured
    let resolvedEnv = { ...process.env, ...env };
    if (projectJavaHome) {
      // Validate JAVA_HOME for security
      if (this.isValidJavaHome(projectJavaHome)) {
        resolvedEnv = withJavaHome(resolvedEnv, projectJavaHome);
        this.logger.appendLine(`[Test] Using project JDK: ${projectJavaHome}`);
      } else {
        this.logger.appendLine(
          `[Test] Ignoring invalid JAVA_HOME configuration: ${projectJavaHome}`,
        );
      }
    }

    // Detect Build Tool by executable name
    const tool = buildToolOf(executable);
    const isMaven = tool === "maven";

    return new Promise((resolve) => {
      const proc = cp.spawn(invocation.executable, invocation.args, {
        cwd,
        env: resolvedEnv,
        shell: invocation.shell,
      });

      // Cancellation
      const cancelSubscriptions = tokens.map((t) =>
        t.onCancellationRequested(() => {
          killProcessTree(proc);
          this.logger.appendLine("Test run cancelled.");
        }),
      );
      const cancelDis = {
        dispose: () => cancelSubscriptions.forEach((d) => d.dispose()),
      };

      let incomplete = false;
      let spawned = proc.pid !== undefined;
      proc.on("spawn", () => {
        spawned = true;
      });
      let foundHttpBlocker = false;
      const onBlocker = () => {
        foundHttpBlocker = true;
      };
      const stdoutMatcher = createHttpBlockerMatcher(onBlocker);
      const stderrMatcher = createHttpBlockerMatcher(onBlocker);
      if (isMaven) {
        proc.stdout.on("data", stdoutMatcher);
        proc.stderr.on("data", stderrMatcher);
      }
      let stdoutFinished = false;
      const stdoutLines = createBoundedLines(({ text, truncated }) => {
        if (truncated) {
          incomplete = true;
          this.logger.appendLine(`${text} [test output line truncated]`);
        } else {
          consumer.processLine(text);
        }
      });
      const failStdout = (message: string) => {
        if (!stdoutFinished) {
          stdoutFinished = true;
          incomplete = true;
          this.logger.appendLine(message);
        }
      };
      proc.stdout.on("data", (chunk: Buffer | string) => {
        if (stdoutFinished) return;
        if (Buffer.isBuffer(chunk)) stdoutLines.write(chunk);
        else
          failStdout("Reading test stdout failed: Expected a raw byte stream");
      });
      proc.stdout.once("end", () => {
        if (!stdoutFinished) {
          stdoutFinished = true;
          stdoutLines.end();
        }
      });
      proc.stdout.on("error", (error: Error) =>
        failStdout(`Reading test stdout failed: ${error.message}`),
      );
      proc.stdout.once("close", () =>
        failStdout("Reading test stdout failed: stream closed before end"),
      );
      pipeBoundedLines(
        proc.stderr,
        ({ text, truncated }) => {
          this.logger.appendLine(
            `[STDERR] ${text}${truncated ? " [test output line truncated]" : ""}`,
          );
        },
        (error) =>
          this.logger.appendLine(
            `Reading test stderr failed: ${error.message}`,
          ),
      );

      let processClosed = false;
      proc.on("close", (code) => {
        if (processClosed) return;
        processClosed = true;
        cancelDis.dispose();

        // Maven Error Handling
        if (isMaven && code !== 0 && foundHttpBlocker) {
          vscode.window
            .showErrorMessage(
              "Maven Blocked HTTP Repository",
              {
                modal: true,
                detail: "Maven 3.8.1+ blocks insecure HTTP repositories.",
              },
              "Open pom.xml",
            )
            .then((s) => {
              if (s === "Open pom.xml") {
                const pom = path.join(cwd, "pom.xml");
                if (fs.existsSync(pom))
                  vscode.workspace
                    .openTextDocument(pom)
                    .then((doc) => vscode.window.showTextDocument(doc));
              }
            });
        }

        // Fallback status for Maven (since we don't have rich events like Gradle init script)
        if (isMaven) {
          // If code != 0, we assume things failed.
          // Consumer.markFailed() might be too broad if we do it for all items.
          // But without granular parsing, it's all we have.
          // For now, let the user check output.
          // TODO: Implement Surefire report parsing or finer Maven output parsing.
        }

        if (code !== 0) {
          this.logger.appendLine(
            `Test execution process exited with code ${code}`,
          );
        }
        resolve({ spawned, incomplete: incomplete || (isMaven && code !== 0) });
      });

      proc.on("error", (err) => {
        if (processClosed) return;
        this.logger.appendLine(`Process error: ${err}`);
        // Node emits close after a spawn error; wait for stream cleanup.
        incomplete = true;
      });
    });
  }
}
