import * as vscode from 'vscode';
import { GradleExecutionService } from './GradleExecutionService';
import { TestService, TestSuite, Test } from './TestService';
import { CoverageService } from './CoverageService';

export class GroovyTestController {
  private readonly ctrl: vscode.TestController;

  constructor(
    context: vscode.ExtensionContext,
    private readonly executionService: GradleExecutionService,
    private readonly testService?: TestService,
    private readonly coverageService?: CoverageService,
  ) {
    this.ctrl = vscode.tests.createTestController(
      'groovy-test-controller',
      'Groovy Tests',
    );
    context.subscriptions.push(this.ctrl);

    this.setupRunProfiles();
    this.setupResolveHandler();
    this.registerCommands(context);
  }

  private registerCommands(context: vscode.ExtensionContext) {
    context.subscriptions.push(
      vscode.commands.registerCommand('groovy.test.run', (args) => this.runTestCommand(args, false)),
      vscode.commands.registerCommand('groovy.test.debug', (args) => this.runTestCommand(args, true))
    );
  }

  private setupRunProfiles() {
    this.ctrl.createRunProfile(
      'Run',
      vscode.TestRunProfileKind.Run,
      (request, token) =>
        this.executionService.runTests(request, token, this.ctrl),
      true,
    );

    this.ctrl.createRunProfile(
      'Debug',
      vscode.TestRunProfileKind.Debug,
      (request, token) => this.executionService.debugTests(request, token),
      false,
    );

    // Coverage profile requires CoverageService
    if (this.coverageService) {
      this.ctrl.createRunProfile(
        'Run with Coverage',
        vscode.TestRunProfileKind.Coverage,
        (request, token) =>
          this.executionService.runTestsWithCoverage(
            request,
            token,
            this.ctrl,
            this.coverageService!,
          ),
        false,
      );
    }
  }

  private setupResolveHandler() {
    // resolveHandler is called when the user expands a test item or refreshes the tree
    this.ctrl.resolveHandler = async (item) => {
      if (!this.testService) {
        return; // No LSP client, skip discovery
      }

      if (item === undefined) {
        // Root level: discover all tests in workspace
        await this.discoverTests();
      }
      // Individual items don't need resolution - children were added during discovery
    };

    // Also add a refresh handler
    this.ctrl.refreshHandler = async (_token) => {
      if (this.testService) {
        await this.discoverTests();
      }
    };
  }

  /**
   * Discover tests from the LSP and populate the TestItem tree.
   */
  private async discoverTests(): Promise<void> {
    if (!this.testService) {
      return;
    }

    const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
    if (!workspaceFolder) {
      return;
    }

    try {
      const suites = await this.testService.discoverTestsInWorkspace(
        workspaceFolder.uri.toString(),
      );

      // Clear existing items
      this.ctrl.items.replace([]);

      // Map suites to TestItems
      for (const suite of suites) {
        const suiteItem = this.createSuiteItem(suite);
        this.ctrl.items.add(suiteItem);
      }
    } catch (error) {
      console.error('Failed to discover tests:', error);
    }
  }

  /**
   * Create a TestItem for a test suite (Spock Specification class).
   */
  private createSuiteItem(suite: TestSuite): vscode.TestItem {
    const uri = vscode.Uri.parse(suite.uri);
    const suiteItem = this.ctrl.createTestItem(
      suite.suite, // id = fully qualified class name
      this.getClassName(suite.suite), // label = simple class name
      uri,
    );

    // Add child test items
    for (const test of suite.tests) {
      const testItem = this.createTestItem(test, suite.suite, uri);
      suiteItem.children.add(testItem);
    }

    return suiteItem;
  }

  /**
   * Create a TestItem for an individual test method.
   */
  private createTestItem(
    test: Test,
    parentId: string,
    uri: vscode.Uri,
  ): vscode.TestItem {
    const testId = `${parentId}.${test.test}`;
    const testItem = this.ctrl.createTestItem(testId, test.test, uri);

    // Set the line range for CodeLens and navigation
    const line = test.line >= 1 ? test.line - 1 : 0;
    testItem.range = new vscode.Range(
      new vscode.Position(line, 0), // 0-indexed
      new vscode.Position(line, 100), // approximate end
    );

    return testItem;
  }

  /**
   * Extract simple class name from fully qualified name.
   */
  private getClassName(fqn: string): string {
    const parts = fqn.split('.');
    return parts[parts.length - 1];
  }


  // Unified command handler
  private async runTestCommand(args: { suite: string; test: string; uri?: string }, debug: boolean) {
    // Validate suite and test names
    if (!args.suite || args.suite.trim() === '') {
      vscode.window.showErrorMessage('Test suite name cannot be empty');
      return;
    }
    if (!args.test || args.test.trim() === '') {
      vscode.window.showErrorMessage('Test name cannot be empty');
      return;
    }

    let item = await this.findTestItem(args.suite, args.test);
    if (!item) {
      // If item not found, it might be because tests weren't discovered yet.
      // Try discovery first.
      await this.discoverTests();
      const retryItem = await this.findTestItem(args.suite, args.test);
      if (!retryItem) {
        // If still not found and we have a URI (e.g., external file), create on-the-fly test item
        if (args.uri) {
          item = this.createOnTheFlyTestItem(args.uri, args.suite, args.test);
        } else {
          vscode.window.showErrorMessage(`Test not found: ${args.suite}.${args.test}`);
          return;
        }
      } else {
        item = retryItem;
      }
    }
    await this.runTestItem(item, debug);
  }

  private async runTestItem(item: vscode.TestItem, debug: boolean) {
    const request = new vscode.TestRunRequest([item]);
    const tokenSource = new vscode.CancellationTokenSource();
    try {
      if (debug) {
        await this.executionService.debugTests(request, tokenSource.token);
      } else {
        await this.executionService.runTests(request, tokenSource.token, this.ctrl);
      }
    } finally {
      tokenSource.dispose();
    }
  }

  private async findTestItem(suiteName: string, testName: string): Promise<vscode.TestItem | undefined> {
    // First find suite
    const suiteItem = this.ctrl.items.get(suiteName);
    if (!suiteItem) {
      return undefined;
    }
    // Then find test child
    // ID format in createTestItem is `${parentId}.${test.test}`
    const testId = `${suiteName}.${testName}`;
    return suiteItem.children.get(testId);
  }

  /**
   * Create a test item on-the-fly for external files that are not in the workspace.
   * This allows running tests from CodeLens on files opened from outside the workspace.
   */
  private createOnTheFlyTestItem(uriString: string, suiteName: string, testName: string): vscode.TestItem {
    // Validate inputs (defensive programming - caller should validate, but guard here too)
    // This protects against future refactoring and ensures method contract is explicit
    if (!suiteName || suiteName.trim() === '') {
      throw new Error('Suite name cannot be empty for on-the-fly test creation');
    }
    if (!testName || testName.trim() === '') {
      throw new Error('Test name cannot be empty for on-the-fly test creation');
    }

    const uri = vscode.Uri.parse(uriString);

    // Check if suite item already exists, if not create it
    let suiteItem = this.ctrl.items.get(suiteName);
    if (!suiteItem) {
      suiteItem = this.ctrl.createTestItem(
        suiteName,
        this.getClassName(suiteName),
        uri,
      );
      this.ctrl.items.add(suiteItem);
    } else if (suiteItem.uri && suiteItem.uri.toString() !== uri.toString()) {
      // Suite exists but from a different file (workspace vs external)
      // Update URI to match the file we're creating tests for
      suiteItem = this.ctrl.createTestItem(
        suiteName,
        this.getClassName(suiteName),
        uri,
      );
      // Delete old suite and add updated one, preserving other test items
      this.ctrl.items.delete(suiteName);
      this.ctrl.items.add(suiteItem);
    }

    // Check if test item already exists under the suite
    const testId = `${suiteName}.${testName}`;
    let testItem = suiteItem.children.get(testId);
    if (!testItem) {
      testItem = this.ctrl.createTestItem(testId, testName, uri);
      // We don't have line information for on-the-fly items, so skip setting range
      suiteItem.children.add(testItem);
    }

    return testItem;
  }
}
