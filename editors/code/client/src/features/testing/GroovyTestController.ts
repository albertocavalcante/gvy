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
    testItem.range = new vscode.Range(
      new vscode.Position(test.line - 1, 0), // 0-indexed
      new vscode.Position(test.line - 1, 100), // approximate end
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
}
