import * as vscode from "vscode";
import * as cp from "child_process";
import * as path from "path";
import * as fs from "fs";
import * as readline from "readline";
import { ITestExecutionService } from "./ITestExecutionService";
import { TestService, TestCommand } from "./TestService";
import { TestEventConsumer } from "./TestEventConsumer";

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

      for (const item of testsToRun) {
        if (token.isCancellationRequested) break;

        // Parse suite/test from Item ID
        // ID format: "fully.qualified.ClassName" (Suite) or "fully.qualified.ClassName.methodName" (Test)
        // We need to pass URI, SuiteName, TestName (optional) to LSP.
        const uri = item.uri?.toString();
        if (!uri) {
          this.logger.appendLine(
            `[Error] Test item ${item.id} has no URI. Skipping.`,
          );
          continue;
        }

        // Metadata is stored in map in Controller, but we don't have access to it here easily.
        // We'll infer from ID and children.
        // Suite: ID has no parent, or check children count
        // Actually, GroovyTestController uses `item.id` as the Suite/Test name.

        let suiteName: string;
        let testName: string | undefined;

        // Heuristic: If item has children, it's a suite. If not, it's a test method (leaves).
        // Or check ID structure.
        // Controller logic: Suite ID = FQN. Test ID = FQN.methodName
        // But Test ID is created as `${suiteName}.${test.test}`

        // We can just rely on the test service to figure it out if we pass the right names.
        // Let's assume the ID *is* the name we want to run, mostly.
        // But we need to split it.

        // If it's a leaf (test method)
        const isSuite = item.children.size > 0;

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
            `[Testing] LSP returned no command for ${item.id}. Build tool may not support test execution.`,
          );
          continue;
        }

        await this.executeCommand(
          command,
          consumer,
          token,
          isSuite ? "gradle" : undefined,
        );
        // Note: 'isSuite' check for gradle is just a placeholder,
        // determining build tool type from command is better.
      }
    } catch (error) {
      this.logger.appendLine(`Test execution error: ${error}`);
    } finally {
      run.end();
    }
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

  private async executeCommand(
    cmd: TestCommand,
    consumer: TestEventConsumer,
    token: vscode.CancellationToken,
    _hint?: string,
  ): Promise<void> {
    const { executable, args, cwd, env } = cmd;
    this.logger.appendLine(
      `[LSP] Executing: ${executable} ${args.join(" ")} (in ${cwd})`,
    );

    // Detect Build Tool by executable name
    const isGradle = executable.includes("gradle");
    const isMaven = executable.includes("mvn");
    const isMavenWrapper = isMaven && executable.includes("mvnw");

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
        env: { ...process.env, ...env },
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
