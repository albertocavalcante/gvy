/**
 * TODO(#715): This is a hacky workaround for Maven support.
 * Proper implementation should:
 * - Move build tool detection to LSP
 * - Use a common ITestExecutionService interface
 * - Support test events/progress reporting
 *
 * See: https://github.com/albertocavalcante/gvy/issues/715
 */
import * as vscode from "vscode";
import * as cp from "child_process";
import * as fs from "fs";
import * as path from "path";
import * as readline from "readline";
import { TestEventConsumer } from "./TestEventConsumer";
import { ITestExecutionService } from "./ITestExecutionService";

export class MavenExecutionService implements ITestExecutionService {
  constructor(private readonly logger: vscode.OutputChannel) {}

  public async runTests(
    request: vscode.TestRunRequest,
    token: vscode.CancellationToken,
    testController: vscode.TestController,
  ): Promise<void> {
    // Determine the project root from test item URI or fall back to workspace
    const testsToRun = request.include ?? [];
    const projectRoot = this.findProjectRoot(testsToRun);

    if (!projectRoot) {
      vscode.window.showErrorMessage(
        "No Maven project found (pom.xml not detected)",
      );
      return;
    }

    const run = testController.createTestRun(request);
    const consumer = new TestEventConsumer(run, this.logger, testController);

    // Build test filter from request
    const testFilter = this.buildTestFilter(request);

    // Register all requested tests with the consumer
    for (const item of testsToRun) {
      consumer.registerTestItem(item.id, item);
      run.enqueued(item);
    }

    try {
      await this.spawnMaven(projectRoot, testFilter, consumer, token);
    } catch (error) {
      this.logger.appendLine(`Maven execution error: ${error}`);
    } finally {
      run.end();
    }
  }

  /**
   * Find the Maven project root by looking for pom.xml in parent directories.
   */
  private findProjectRoot(
    testItems: readonly vscode.TestItem[],
  ): string | undefined {
    // Try to get URI from first test item
    const firstItem = testItems[0];
    if (firstItem?.uri) {
      const projectRoot = this.findPomDirectory(firstItem.uri.fsPath);
      if (projectRoot) {
        return projectRoot;
      }
    }

    // Fall back to first workspace folder
    const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
    if (workspaceFolder) {
      const projectRoot = this.findPomDirectory(workspaceFolder.uri.fsPath);
      if (projectRoot) {
        return projectRoot;
      }
    }

    return undefined;
  }

  /**
   * Walk up the directory tree to find pom.xml
   */
  private findPomDirectory(startPath: string): string | undefined {
    let current = startPath;

    // Handle file paths - start from directory
    if (fs.existsSync(current) && fs.statSync(current).isFile()) {
      current = path.dirname(current);
    }

    // Walk up looking for pom.xml
    while (current !== path.dirname(current)) {
      // Stop at filesystem root
      const pomPath = path.join(current, "pom.xml");
      if (fs.existsSync(pomPath)) {
        this.logger.appendLine(
          `[Testing] Found Maven project root: ${current}`,
        );
        return current;
      }
      current = path.dirname(current);
    }

    return undefined;
  }

  public async debugTests(
    _request: vscode.TestRunRequest,
    _token: vscode.CancellationToken,
  ): Promise<void> {
    this.logger.appendLine(
      "MavenExecutionService: debugTests requested (not implemented)",
    );
    vscode.window.showWarningMessage(
      "Debug tests not yet supported for Maven projects",
    );
  }

  public async runTestsWithCoverage(
    _request: vscode.TestRunRequest,
    _token: vscode.CancellationToken,
    _testController: vscode.TestController,
    _coverageService: unknown,
  ): Promise<void> {
    this.logger.appendLine(
      "MavenExecutionService: runTestsWithCoverage requested (not implemented)",
    );
    vscode.window.showWarningMessage(
      "Coverage not yet supported for Maven projects",
    );
  }

  /**
   * Build Maven Surefire test filter.
   * Format: -Dtest="ClassName#methodName" for methods, "ClassName" for suites
   */
  private buildTestFilter(request: vscode.TestRunRequest): string[] {
    const testsToRun = request.include ?? [];
    if (testsToRun.length === 0) {
      return [];
    }

    // Convert test IDs to Maven Surefire format
    const testPatterns = testsToRun.map((item) => {
      // A suite (test class) has children; use FQCN directly
      if (item.children.size > 0) {
        return item.id;
      }

      // It's a single test method: "com.example.MySpec.test name" -> "com.example.MySpec#test name"
      const id = item.id;
      const lastDotIndex = id.lastIndexOf(".");
      if (lastDotIndex === -1) {
        return id; // Fallback: no dot found
      }

      const className = id.substring(0, lastDotIndex);
      const methodName = id.substring(lastDotIndex + 1);
      return `${className}#${methodName}`;
    });

    // Quote the pattern to handle spaces in test names (e.g., "single-argument capture")
    // Without quotes, shell interprets spaces as arg separators, causing Maven errors
    return [`-Dtest="${testPatterns.join(",")}"`];
  }

  private async spawnMaven(
    cwd: string,
    testFilter: string[],
    consumer: TestEventConsumer,
    token: vscode.CancellationToken,
  ): Promise<void> {
    return new Promise((resolve, reject) => {
      // Look for Maven wrapper first, fall back to mvn
      const mvnWrapper = process.platform === "win32" ? "mvnw.cmd" : "mvnw";
      const mvnWrapperPath = path.join(cwd, mvnWrapper);
      const hasMvnWrapper = fs.existsSync(mvnWrapperPath);
      const mvnCmd = hasMvnWrapper ? mvnWrapperPath : "mvn";

      const args = ["test", ...testFilter, "-q"];

      this.logger.appendLine(`Running: ${mvnCmd} ${args.join(" ")}`);

      const proc = cp.spawn(mvnCmd, args, {
        cwd,
        shell: !hasMvnWrapper, // Use shell for global mvn, not for wrapper
        env: { ...process.env },
      });

      // Track cancellation state
      let wasCancelled = false;
      let foundHttpBlocker = false;
      const httpBlockerPattern =
        /maven-default-http-blocker|Blocked mirror for repositories/i;

      // Handle cancellation
      const cancelListener = token.onCancellationRequested(() => {
        wasCancelled = true;
        proc.kill("SIGTERM");
        this.logger.appendLine("Test run cancelled");
      });

      // Process stdout line by line
      const rl = readline.createInterface({ input: proc.stdout });
      rl.on("line", (line) => {
        // processLine handles logging for non-JSON lines
        consumer.processLine(line);
        if (httpBlockerPattern.test(line)) {
          foundHttpBlocker = true;
        }
      });

      // Log and capture stderr
      let collectedStderr = "";
      proc.stderr.on("data", (data) => {
        const str = data.toString();
        collectedStderr += str;
        this.logger.appendLine(`[STDERR] ${str}`);
        if (httpBlockerPattern.test(str)) {
          foundHttpBlocker = true;
        }
      });

      proc.on("close", (code) => {
        cancelListener.dispose();
        rl.close();

        // Don't report results if cancelled - leave tests in enqueued state
        if (wasCancelled) {
          this.logger.appendLine("Test run was cancelled");
          resolve();
          return;
        }

        // Check for specific Maven errors like blocked HTTP repositories (Maven 3.8.1+)
        if (
          code !== 0 &&
          (foundHttpBlocker || httpBlockerPattern.test(collectedStderr))
        ) {
          vscode.window
            .showErrorMessage(
              "Maven Blocked HTTP Repository",
              {
                modal: true,
                detail:
                  "Maven 3.8.1+ blocks insecure HTTP repositories by default.\n\n" +
                  "Reference: https://maven.apache.org/docs/3.8.1/release-notes.html#cve-2021-26291",
              },
              "Open pom.xml",
            )
            .then((selection) => {
              if (selection === "Open pom.xml") {
                const pomPath = path.join(cwd, "pom.xml");
                if (fs.existsSync(pomPath)) {
                  vscode.workspace
                    .openTextDocument(pomPath)
                    .then((doc) => vscode.window.showTextDocument(doc));
                }
              }
            });
        }

        // Mark tests as passed/failed based on exit code
        // TODO(#715): Parse actual test results from Surefire reports
        const testsToRun = consumer.getAllRegisteredItems();
        for (const item of testsToRun) {
          if (code === 0) {
            consumer.markPassed(item);
          } else {
            consumer.markFailed(
              item,
              "Maven test failed (see output for details)",
            );
          }
        }

        if (code === 0) {
          this.logger.appendLine("Maven test run completed successfully");
          resolve();
        } else {
          this.logger.appendLine(`Maven exited with code ${code}`);
          resolve(); // Don't reject, tests may have failed but execution completed
        }
      });

      proc.on("error", (err) => {
        cancelListener.dispose();
        rl.close();
        reject(err);
      });
    });
  }
}
