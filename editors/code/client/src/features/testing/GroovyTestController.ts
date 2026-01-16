import * as vscode from "vscode";
import { ITestExecutionService } from "./ITestExecutionService";
import { TestService, TestSuite, Test } from "./TestService";
import { CoverageService } from "./CoverageService";

/**
 * Tag for runnable test items - enables native Test Explorer play buttons.
 */
export const runnableTag = new vscode.TestTag("runnable");

/**
 * Codicon labels for test items in Test Explorer.
 */
function getCodiconLabel(kind: "suite" | "test"): string {
  return kind === "suite" ? "$(symbol-class)" : "$(symbol-method)";
}

/**
 * Metadata for test items stored in cache.
 */
interface TestItemMetadata {
  kind: "suite" | "test";
  suiteName: string;
  line?: number;
}

/** Cache for test item metadata. */
const dataCache = new WeakMap<vscode.TestItem, TestItemMetadata>();

export class GroovyTestController {
  private readonly ctrl: vscode.TestController;
  private readonly subscriptions: vscode.Disposable[] = [];

  constructor(
    context: vscode.ExtensionContext,
    // TODO(#715): Using interface for hacky Maven support
    private readonly executionService: ITestExecutionService,
    private readonly testService?: TestService,
    private readonly coverageService?: CoverageService,
  ) {
    this.ctrl = vscode.tests.createTestController(
      "groovy-test-controller",
      "Groovy Tests",
    );
    context.subscriptions.push(this.ctrl);

    this.setupRunProfiles();
    this.setupResolveHandler();
    this.setupFileWatchers();
    this.setupDocumentHandlers();
    this.processVisibleEditors();
    this.registerCommands(context);

    // Initial discovery if LSP is ready
    if (this.testService) {
      this.refreshTests();
    }

    // Cleanup subscriptions on dispose
    context.subscriptions.push({ dispose: () => this.dispose() });
  }

  /**
   * Dispose of subscriptions and watchers.
   */
  dispose(): void {
    if (this.refreshTimeout) {
      clearTimeout(this.refreshTimeout);
    }
    this.subscriptions.forEach((s) => s.dispose());
    this.subscriptions.length = 0;
  }

  /**
   * Setup file system watchers for test files.
   * Triggers LSP discovery when test files are created/changed/deleted.
   */
  private setupFileWatchers(): void {
    const watcher = vscode.workspace.createFileSystemWatcher(
      "**/*{Spec,Test}.groovy",
    );
    this.subscriptions.push(watcher);

    this.subscriptions.push(
      watcher.onDidCreate(() => this.refreshTests()),
      watcher.onDidChange(() => this.refreshTests()),
      watcher.onDidDelete(() => this.refreshTests()),
    );
  }

  /**
   * Setup document event handlers for live test discovery.
   */
  private setupDocumentHandlers(): void {
    this.subscriptions.push(
      vscode.workspace.onDidOpenTextDocument((doc) => {
        if (this.isTestFile(doc.uri)) {
          this.refreshTests();
        }
      }),
      vscode.workspace.onDidSaveTextDocument((doc) => {
        if (this.isTestFile(doc.uri)) {
          this.refreshTests();
        }
      }),
    );
  }

  /**
   * Process visible editors on startup to discover tests immediately.
   */
  private processVisibleEditors(): void {
    vscode.window.visibleTextEditors.forEach((editor) => {
      if (this.isTestFile(editor.document.uri)) {
        this.refreshTests();
      }
    });
  }

  /**
   * Check if a URI is a test file based on naming convention.
   */
  private isTestFile(uri: vscode.Uri): boolean {
    const path = uri.fsPath;
    return path.endsWith("Spec.groovy") || path.endsWith("Test.groovy");
  }

  /**
   * Refresh tests with debouncing to avoid excessive LSP calls.
   */
  private refreshTimeout: ReturnType<typeof setTimeout> | undefined;
  private refreshTests(): void {
    if (this.refreshTimeout) {
      clearTimeout(this.refreshTimeout);
    }
    this.refreshTimeout = setTimeout(() => {
      this.discoverTests();
    }, 300);
  }

  private registerCommands(context: vscode.ExtensionContext) {
    context.subscriptions.push(
      vscode.commands.registerCommand("groovy.test.run", (args) =>
        this.runTestCommand(args, false),
      ),
      vscode.commands.registerCommand("groovy.test.debug", (args) =>
        this.runTestCommand(args, true),
      ),
      vscode.commands.registerCommand("groovy.test.runWithCoverage", (args) =>
        this.runTestCommand(args, false, true),
      ),
      vscode.commands.registerCommand("groovy.test.runAll", () =>
        this.runAllTests(),
      ),
      vscode.commands.registerCommand("groovy.test.runCurrentFile", () =>
        this.runCurrentFileTests(),
      ),
    );
  }

  private async runAllTests(): Promise<void> {
    await this.discoverTests();
    const items: vscode.TestItem[] = [];
    this.ctrl.items.forEach((item) => items.push(item));
    if (items.length === 0) {
      vscode.window.showInformationMessage("No tests found in workspace");
      return;
    }
    const request = new vscode.TestRunRequest(items);
    const tokenSource = new vscode.CancellationTokenSource();
    try {
      await this.executionService.runTests(
        request,
        tokenSource.token,
        this.ctrl,
      );
    } finally {
      tokenSource.dispose();
    }
  }

  private async runCurrentFileTests(): Promise<void> {
    const editor = vscode.window.activeTextEditor;
    if (!editor || !this.isTestFile(editor.document.uri)) {
      vscode.window.showWarningMessage("No test file is currently open");
      return;
    }

    // Ensure tests are discovered for this file
    await this.discoverTests();

    const fileUri = editor.document.uri.toString();
    const items: vscode.TestItem[] = [];
    this.ctrl.items.forEach((item) => {
      // Check if the item's URI matches the current file
      // Note: item.uri might be undefined for some items, handle safely
      if (item.uri?.toString() === fileUri) {
        items.push(item);
      }
    });

    if (items.length === 0) {
      vscode.window.showWarningMessage("No tests found in current file");
      return;
    }

    const request = new vscode.TestRunRequest(items);
    const tokenSource = new vscode.CancellationTokenSource();
    try {
      await this.executionService.runTests(
        request,
        tokenSource.token,
        this.ctrl,
      );
    } finally {
      tokenSource.dispose();
    }
  }

  private setupRunProfiles() {
    this.ctrl.createRunProfile(
      "Run",
      vscode.TestRunProfileKind.Run,
      (request, token) =>
        this.executionService.runTests(request, token, this.ctrl),
      true,
    );

    this.ctrl.createRunProfile(
      "Debug",
      vscode.TestRunProfileKind.Debug,
      (request, token) => this.executionService.debugTests(request, token),
      false,
    );

    // Coverage profile requires CoverageService and execution service support
    if (this.coverageService && this.executionService.runTestsWithCoverage) {
      this.ctrl.createRunProfile(
        "Run with Coverage",
        vscode.TestRunProfileKind.Coverage,
        (request, token) =>
          this.executionService.runTestsWithCoverage!(
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
      console.error("Failed to discover tests:", error);
    }
  }

  /**
   * Create a TestItem for a test suite (Spock Specification class).
   */
  private createSuiteItem(suite: TestSuite): vscode.TestItem {
    const uri = vscode.Uri.parse(suite.uri);
    const label = `${getCodiconLabel("suite")} ${this.getClassName(suite.suite)}`;
    const suiteItem = this.ctrl.createTestItem(
      suite.suite, // id = fully qualified class name
      label,
      uri,
    );

    // Enable native play buttons and lazy loading
    suiteItem.tags = [runnableTag];
    suiteItem.canResolveChildren = true;

    // Store metadata
    dataCache.set(suiteItem, { kind: "suite", suiteName: suite.suite });

    // Add child test items first to determine suite range
    let minLine = Number.MAX_SAFE_INTEGER;
    for (const test of suite.tests) {
      const testItem = this.createTestItem(test, suite.suite, uri);
      suiteItem.children.add(testItem);
      if (test.line < minLine) {
        minLine = test.line;
      }
    }

    // Set suite range: estimate class declaration is a few lines before first test
    // Use line 0 as minimum to avoid negative values
    const ESTIMATED_LINES_ABOVE_FIRST_TEST = 5;
    const APPROXIMATE_LINE_LENGTH = 100;
    if (minLine !== Number.MAX_SAFE_INTEGER && minLine >= 0) {
      const estimatedClassLine = Math.max(
        0,
        minLine - ESTIMATED_LINES_ABOVE_FIRST_TEST,
      );
      suiteItem.range = new vscode.Range(
        new vscode.Position(estimatedClassLine, 0),
        new vscode.Position(estimatedClassLine, APPROXIMATE_LINE_LENGTH),
      );
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
    const label = `${getCodiconLabel("test")} ${test.test}`;
    const testItem = this.ctrl.createTestItem(testId, label, uri);

    // Enable native play buttons
    testItem.tags = [runnableTag];
    testItem.canResolveChildren = false; // Test methods are leaves

    // Set the line range for CodeLens and navigation
    const line = test.line >= 1 ? test.line - 1 : 0;
    testItem.range = new vscode.Range(
      new vscode.Position(line, 0), // 0-indexed
      new vscode.Position(line, 100), // approximate end
    );

    // Preserve source order with sortText
    testItem.sortText = String(test.line).padStart(6, "0");

    // Store metadata
    dataCache.set(testItem, {
      kind: "test",
      suiteName: parentId,
      line: test.line,
    });

    return testItem;
  }

  /**
   * Extract simple class name from fully qualified name.
   */
  private getClassName(fqn: string): string {
    const parts = fqn.split(".");
    return parts[parts.length - 1];
  }

  /**
   * Check if a URI is within the current workspace.
   */
  private isInWorkspace(uriString: string): boolean {
    const uri = vscode.Uri.parse(uriString);
    return vscode.workspace.getWorkspaceFolder(uri) !== undefined;
  }

  // Unified command handler
  private async runTestCommand(
    args: { suite: string; test: string; uri?: string },
    debug: boolean,
    withCoverage: boolean = false,
  ) {
    // Validate suite name
    if (!args.suite || args.suite.trim() === "") {
      vscode.window.showErrorMessage("Test suite name cannot be empty");
      return;
    }

    // Handle wildcard: run entire suite
    const isWildcard = args.test === "*";
    if (!isWildcard && (!args.test || args.test.trim() === "")) {
      vscode.window.showErrorMessage("Test name cannot be empty");
      return;
    }

    // Check if file is outside workspace - cannot run tests from external projects
    // TODO(#714): Support external file test execution by detecting their project root
    if (args.uri && !this.isInWorkspace(args.uri)) {
      vscode.window.showWarningMessage(
        "Cannot run test: file is outside the current workspace. " +
          "Open the file's project folder to run its tests.",
      );
      return;
    }

    // If wildcard, try to find and run the suite item directly first
    if (isWildcard) {
      const suiteItem = this.ctrl.items.get(args.suite);
      if (suiteItem) {
        await this.runTestItem(suiteItem, debug, withCoverage);
        return;
      }
      // If suite not found, discovery might be needed
    }

    let item = isWildcard
      ? undefined
      : await this.findTestItem(args.suite, args.test);

    if (!item) {
      // If item not found, it might be because tests weren't discovered yet.
      // Try discovery first.
      await this.discoverTests();

      if (isWildcard) {
        // Retry finding suite
        const retrySuiteItem = this.ctrl.items.get(args.suite);
        if (retrySuiteItem) {
          await this.runTestItem(retrySuiteItem, debug, withCoverage);
          return;
        }
      } else {
        const retryItem = await this.findTestItem(args.suite, args.test);
        if (retryItem) {
          item = retryItem;
        }
      }

      if (!item && !isWildcard) {
        // If still not found and we have a URI (e.g., external file), create on-the-fly test item
        if (args.uri) {
          item = this.createOnTheFlyTestItem(args.uri, args.suite, args.test);
        } else {
          vscode.window.showErrorMessage(
            `Test not found: ${args.suite}.${args.test}`,
          );
          return;
        }
      } else if (!item && isWildcard) {
        // If wildcard and still no suite found... handle on-the-fly suite?
        // For now, if we can't find the suite after discovery, we can't run "all tests" reliably via controller without a suite item.
        // But we could potentially create an on-the-fly SUITE item.
        if (args.uri) {
          // Create on-the-fly SUITE item (TODO: extract method)
          // simplified logic for now
          vscode.window.showErrorMessage(
            `Test suite not found for wildcard run: ${args.suite}`,
          );
          return;
        }
        return;
      }
    }

    if (item) {
      await this.runTestItem(item, debug, withCoverage);
    }
  }

  private async runTestItem(
    item: vscode.TestItem,
    debug: boolean,
    withCoverage: boolean = false,
  ) {
    const request = new vscode.TestRunRequest([item]);
    const tokenSource = new vscode.CancellationTokenSource();
    try {
      if (debug) {
        await this.executionService.debugTests(request, tokenSource.token);
      } else if (
        withCoverage &&
        this.executionService.runTestsWithCoverage &&
        this.coverageService
      ) {
        await this.executionService.runTestsWithCoverage(
          request,
          tokenSource.token,
          this.ctrl,
          this.coverageService,
        );
      } else {
        await this.executionService.runTests(
          request,
          tokenSource.token,
          this.ctrl,
        );
      }
    } finally {
      tokenSource.dispose();
    }
  }

  private async findTestItem(
    suiteName: string,
    testName: string,
  ): Promise<vscode.TestItem | undefined> {
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
  private createOnTheFlyTestItem(
    uriString: string,
    suiteName: string,
    testName: string,
  ): vscode.TestItem {
    // Validate inputs (defensive programming - caller should validate, but guard here too)
    // This protects against future refactoring and ensures method contract is explicit
    if (!suiteName || suiteName.trim() === "") {
      throw new Error(
        "Suite name cannot be empty for on-the-fly test creation",
      );
    }
    if (!testName || testName.trim() === "") {
      throw new Error("Test name cannot be empty for on-the-fly test creation");
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
