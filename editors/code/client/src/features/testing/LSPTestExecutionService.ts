import * as vscode from "vscode";
import * as cp from "child_process";
import * as path from "path";
import * as fs from "fs";
import * as readline from "readline";
import { ITestExecutionService } from "./ITestExecutionService";
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

export class LSPTestExecutionService implements ITestExecutionService {
  private readonly initScriptPath: string;

  constructor(
    private readonly testService: TestService,
    private readonly logger: vscode.OutputChannel,
    extensionPath: string,
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
    const consumer = new TestEventConsumer(run, this.logger, testController);
    const testsToRun = request.include ?? [];

    // Register items with consumer
    for (const item of testsToRun) {
      consumer.registerTestItem(item.id, item);
      run.enqueued(item);
    }

    try {
      // Group tests by suite/file to minimize LSP calls if possible,
      // but for now, we'll process the first item to get the command
      // and assume the command runs all requested tests if we pass them effectively.
      // Actually, LSP 'runTest' might handle one test at a time or a suite.
      // If the user selected multiple unrelated tests, we might need multiple runs.
      // For simplicity and to match current behavior, we'll assume they belong to the same project context.

      // TODO: If the request contains multiple distinct items, the current LSP API
      // might need to be called for each, OR we trust the LSP to build a command for all.
      // The current `groovy/runTest` takes a single URI/Suite/Test.
      // So if we have multiple items, we might need to iterate.
      // However, usually `args` returned by LSP can be manipulated or we call it once.

      // If we have mixed items, running them all in one go is tricky if they require different commands.
      // We'll iterate through items and run them sequentially if they yield different commands,
      // or try to batch them.
      // For now, let's implement the loop:

      // Get workspace URI for LSP requests
      const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
      const workspaceUri = workspaceFolder?.uri.toString() || "";

      let isMavenExecution = false;

      for (const item of testsToRun) {
        if (token.isCancellationRequested) break;

        // Parse suite/test from Item ID
        const uri = item.uri?.toString();
        if (!uri) {
          this.logger.appendLine(
            `[Error] Test item ${item.id} has no URI. Skipping.`,
          );
          continue;
        }

        let suiteName: string;
        let testName: string | undefined;

        // Heuristic: If the item ID contains a dot, it's a test method (ClassName.methodName). Otherwise, it's a suite.
        const isSuite = !item.id.includes(".");

        if (isSuite) {
          suiteName = item.id;
          testName = undefined;
        } else {
          // It's a method
          const lastDot = item.id.lastIndexOf(".");
          if (lastDot > 0) {
            suiteName = item.id.substring(0, lastDot);
            testName = item.id.substring(lastDot + 1);
          } else {
            suiteName = item.id;
          }
        }

        const command = await this.testService.getTestCommand(
          uri,
          suiteName,
          testName,
        );

        if (!command) {
          this.logger.appendLine(
            `[Testing] LSP returned no test execution command for test item '${item.id}'. This may indicate a configuration issue for this test or that the build tool does not support running it.`,
          );
          run.errored(
            item,
            new vscode.TestMessage(
              `No test execution command was returned for test item '${item.id}'. This may indicate a configuration issue for this test or that the build tool does not support running it.`,
            ),
          );
          continue;
        }

        // Execute the test command with optional coverage
        const executableName = path.basename(command.executable);
        const isGradle = executableName.startsWith("gradle");
        const isMaven =
          executableName === "mvn" ||
          executableName === "mvnw" ||
          executableName.startsWith("mvn.");

        // Track if any Maven execution occurred
        if (isMaven) {
          isMavenExecution = true;
        }

        // Clone command.args to avoid mutation
        const coverageArgs = [...command.args];
        if (options.withCoverage) {
          // Append JaCoCo report generation
          if (isGradle) {
            coverageArgs.push("jacocoTestReport");
          } else if (isMaven) {
            // For Maven, add jacoco:report goal after test
            // Requires jacoco-maven-plugin in pom.xml
            coverageArgs.push("jacoco:report");
          }
        }

        await this.executeCommand(
          { ...command, args: coverageArgs },
          consumer,
          token,
        );
      }

      // For Maven, fetch and apply Surefire results from LSP (once after all tests complete)
      if (isMavenExecution && !token.isCancellationRequested) {
        await this.applyTestResults(workspaceUri, run, testsToRun, consumer);
      }

      // After all tests complete, fetch and add coverage if requested
      if (
        options.withCoverage &&
        options.coverageService &&
        !token.isCancellationRequested
      ) {
        await options.coverageService.addCoverageToRun(run, workspaceUri);
      }
    } catch (error) {
      this.logger.appendLine(`Test execution error: ${error}`);
    } finally {
      consumer.clear();
      run.end();
    }
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
    _consumer: TestEventConsumer,
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

        if (result) {
          this.applyResultToItem(run, item, result);
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

  private async executeCommand(
    cmd: TestCommand,
    consumer: TestEventConsumer,
    token: vscode.CancellationToken,
  ): Promise<void> {
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
        resolvedEnv = {
          ...resolvedEnv,
          JAVA_HOME: projectJavaHome,
          PATH: `${projectJavaHome}/bin${path.delimiter}${resolvedEnv.PATH || ""}`,
        };
        this.logger.appendLine(`[Test] Using project JDK: ${projectJavaHome}`);
      } else {
        this.logger.appendLine(
          `[Test] Ignoring invalid JAVA_HOME configuration: ${projectJavaHome}`,
        );
      }
    }

    // Detect Build Tool by executable name
    const executableName = path.basename(executable);
    const isGradle = executableName.startsWith("gradle");
    const isMaven =
      executableName === "mvn" ||
      executableName === "mvnw" ||
      executableName.startsWith("mvn.");
    const isMavenWrapper = isMaven && executableName === "mvnw";

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

    return new Promise((resolve) => {
      const proc = cp.spawn(executable, finalArgs, {
        cwd,
        env: resolvedEnv,
        // Shell usage:
        // - Maven Wrapper (mvnw): shell: false (executable script)
        // - Global Maven (mvn): shell: true (needed for Windows batch/cmd shims)
        // - Gradle (all): shell: false (executable script or binary)
        shell: isMaven && !isMavenWrapper,
      });

      // Cancellation
      const cancelDis = token.onCancellationRequested(() => {
        proc.kill();
        this.logger.appendLine("Test run cancelled.");
      });

      // Output Parsing
      const rl = readline.createInterface({ input: proc.stdout });

      // Maven specific state
      let foundHttpBlocker = false;
      const httpBlockerPattern =
        /maven-default-http-blocker|Blocked mirror for repositories/i;

      rl.on("line", (line) => {
        if (isGradle) {
          consumer.processLine(line);
        } else {
          // Basic Maven parsing or just log
          consumer.processLine(line); // It logs internally
          if (isMaven && httpBlockerPattern.test(line)) {
            foundHttpBlocker = true;
          }
        }
      });

      proc.stderr.on("data", (data) => {
        const str = data.toString();
        this.logger.appendLine(`[STDERR] ${str}`);
        if (isMaven) {
          if (httpBlockerPattern.test(str)) {
            foundHttpBlocker = true;
          }
        }
      });

      proc.on("close", (code) => {
        cancelDis.dispose();
        rl.close();

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
        resolve();
      });

      proc.on("error", (err) => {
        cancelDis.dispose();
        rl.close();
        this.logger.appendLine(`Process error: ${err}`);
        // Caller handles failure via test item state, not promise rejection
        resolve();
      });
    });
  }
}
