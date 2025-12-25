import * as vscode from 'vscode';
import * as cp from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import * as readline from 'readline';
import { TestEventConsumer } from './TestEventConsumer';
import { CoverageService } from './CoverageService';

export class GradleExecutionService {
  private readonly initScriptPath: string;

  constructor(
    private readonly logger: vscode.OutputChannel,
    extensionPath: string,
  ) {
    this.initScriptPath = path.join(
      extensionPath,
      'resources',
      'test-events.init.gradle',
    );
  }

  public async runTests(
    request: vscode.TestRunRequest,
    token: vscode.CancellationToken,
    testController: vscode.TestController,
  ): Promise<void> {
    const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
    if (!workspaceFolder) {
      vscode.window.showErrorMessage('No workspace folder open');
      return;
    }

    const run = testController.createTestRun(request);
    const consumer = new TestEventConsumer(run, this.logger, testController);

    // Build test filter from request
    const testFilter = this.buildTestFilter(request);

    // Register all requested tests with the consumer
    const testsToRun = request.include ?? [];
    for (const item of testsToRun) {
      consumer.registerTestItem(item.id, item);
      run.enqueued(item);
    }

    try {
      await this.spawnGradle(
        workspaceFolder.uri.fsPath,
        testFilter,
        consumer,
        token,
      );
    } catch (error) {
      this.logger.appendLine(`Gradle execution error: ${error}`);
    } finally {
      run.end();
    }
  }

  public async runTestsWithCoverage(
    request: vscode.TestRunRequest,
    token: vscode.CancellationToken,
    testController: vscode.TestController,
    coverageService: CoverageService,
  ): Promise<void> {
    const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
    if (!workspaceFolder) {
      vscode.window.showErrorMessage('No workspace folder open');
      return;
    }

    const run = testController.createTestRun(request);
    const consumer = new TestEventConsumer(run, this.logger, testController);

    // Build test filter from request
    const testFilter = this.buildTestFilter(request);

    // Register all requested tests with the consumer
    const testsToRun = request.include ?? [];
    for (const item of testsToRun) {
      consumer.registerTestItem(item.id, item);
      run.enqueued(item);
    }

    try {
      // Run tests with JaCoCo coverage report
      await this.spawnGradleWithCoverage(
        workspaceFolder.uri.fsPath,
        testFilter,
        consumer,
        token,
      );

      // Parse and add coverage data
      await coverageService.addCoverageToRun(run, workspaceFolder.uri.fsPath);
    } catch (error) {
      this.logger.appendLine(`Gradle execution error: ${error}`);
    } finally {
      run.end();
    }
  }

  public async debugTests(
    _request: vscode.TestRunRequest,
    _token: vscode.CancellationToken,
  ): Promise<void> {
    this.logger.appendLine(
      'GradleExecutionService: debugTests requested (not implemented)',
    );
    // Future implementation will go here
  }

  private buildTestFilter(request: vscode.TestRunRequest): string[] {
    const filters: string[] = [];
    const testsToRun = request.include ?? [];

    for (const item of testsToRun) {
      // item.id could be "com.example.MySpec" (suite) or "com.example.MySpec.testMethod" (test)
      filters.push('--tests', item.id);
    }

    return filters;
  }

  private async spawnGradle(
    cwd: string,
    testFilter: string[],
    consumer: TestEventConsumer,
    token: vscode.CancellationToken,
  ): Promise<void> {
    return new Promise((resolve, reject) => {
      // Use absolute path to avoid shell execution
      const gradleWrapper =
        process.platform === 'win32' ? 'gradlew.bat' : 'gradlew';
      const gradleCmd = path.join(cwd, gradleWrapper);

      // Verify gradlew exists
      if (!fs.existsSync(gradleCmd)) {
        reject(new Error(`Gradle wrapper not found: ${gradleCmd}`));
        return;
      }

      const args = [
        '--init-script',
        this.initScriptPath,
        'test',
        ...testFilter,
        '--console=plain',
      ];

      this.logger.appendLine(`Running: ${gradleCmd} ${args.join(' ')}`);

      // Security: shell: false prevents command injection via test names
      const proc = cp.spawn(gradleCmd, args, {
        cwd,
        shell: false,
        env: { ...process.env },
      });

      // Handle cancellation
      const cancelListener = token.onCancellationRequested(() => {
        proc.kill('SIGTERM');
        this.logger.appendLine('Test run cancelled');
      });

      // Process stdout line by line
      const rl = readline.createInterface({ input: proc.stdout });
      rl.on('line', (line) => consumer.processLine(line));

      // Log stderr
      proc.stderr.on('data', (data) => {
        this.logger.appendLine(`[STDERR] ${data.toString()}`);
      });

      proc.on('close', (code) => {
        cancelListener.dispose();
        rl.close();
        if (code === 0) {
          this.logger.appendLine('Gradle test run completed successfully');
          resolve();
        } else {
          this.logger.appendLine(`Gradle exited with code ${code}`);
          resolve(); // Don't reject, tests may have failed but execution completed
        }
      });

      proc.on('error', (err) => {
        cancelListener.dispose();
        rl.close();
        reject(err);
      });
    });
  }

  /**
   * Spawn Gradle with both test and jacocoTestReport tasks for coverage.
   */
  private async spawnGradleWithCoverage(
    cwd: string,
    testFilter: string[],
    consumer: TestEventConsumer,
    token: vscode.CancellationToken,
  ): Promise<void> {
    return new Promise((resolve, reject) => {
      const gradleWrapper =
        process.platform === 'win32' ? 'gradlew.bat' : 'gradlew';
      const gradleCmd = path.join(cwd, gradleWrapper);

      if (!fs.existsSync(gradleCmd)) {
        reject(new Error(`Gradle wrapper not found: ${gradleCmd}`));
        return;
      }

      // Run both test and jacocoTestReport
      const args = [
        '--init-script',
        this.initScriptPath,
        'test',
        'jacocoTestReport',
        ...testFilter,
        '--console=plain',
      ];

      this.logger.appendLine(`Running with coverage: ${gradleCmd} ${args.join(' ')}`);

      const proc = cp.spawn(gradleCmd, args, {
        cwd,
        shell: false,
        env: { ...process.env },
      });

      const cancelListener = token.onCancellationRequested(() => {
        proc.kill('SIGTERM');
        this.logger.appendLine('Test run cancelled');
      });

      const rl = readline.createInterface({ input: proc.stdout });
      rl.on('line', (line) => consumer.processLine(line));

      proc.stderr.on('data', (data) => {
        this.logger.appendLine(`[STDERR] ${data.toString()}`);
      });

      proc.on('close', (code) => {
        cancelListener.dispose();
        rl.close();
        if (code === 0) {
          this.logger.appendLine('Gradle test run with coverage completed successfully');
          resolve();
        } else {
          this.logger.appendLine(`Gradle exited with code ${code}`);
          resolve();
        }
      });

      proc.on('error', (err) => {
        cancelListener.dispose();
        rl.close();
        reject(err);
      });
    });
  }
}
