// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

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

interface TestCommandArgs {
  suite: string;
  test: string;
  uri?: string;
}

function decodeTestCommandArgs(
  input: unknown,
): { args: TestCommandArgs } | { error: string } {
  if (typeof input !== "object" || input === null || Array.isArray(input)) {
    return { error: "A test command requires a suite and test target" };
  }
  if (
    !("suite" in input) ||
    typeof input.suite !== "string" ||
    input.suite.trim() === ""
  ) {
    return { error: "Test suite name must be a non-empty string" };
  }
  if (
    !("test" in input) ||
    typeof input.test !== "string" ||
    input.test.trim() === ""
  ) {
    return { error: "Test name must be a non-empty string" };
  }

  let uri: string | undefined;
  if ("uri" in input && input.uri !== undefined) {
    if (typeof input.uri !== "string" || input.uri.trim() === "") {
      return { error: "Test URI must be a non-empty URI string" };
    }
    try {
      uri = vscode.Uri.parse(input.uri, true).toString();
    } catch {
      return { error: "Test URI must be a valid URI string" };
    }
  }
  return { args: { suite: input.suite, test: input.test, uri } };
}

export class GroovyTestController {
  private readonly ctrl: vscode.TestController;
  private readonly subscriptions: vscode.Disposable[] = [];
  private disposed = false;
  private discoveryVersion = 0;

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

    // Cleanup subscriptions on dispose
    context.subscriptions.push({ dispose: () => this.dispose() });
  }

  /**
   * Dispose of subscriptions and watchers.
   */
  dispose(): void {
    this.disposed = true;
    this.discoveryVersion++;
    if (this.refreshTimeout) {
      clearTimeout(this.refreshTimeout);
      this.refreshTimeout = undefined;
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
  /** Re-runs (debounced) discovery, e.g. once the language server is up. */
  refreshTests(): void {
    if (this.disposed) {
      return;
    }
    // A refresh after a restart supersedes any previous client's result.
    this.discoveryVersion++;
    if (this.refreshTimeout) {
      clearTimeout(this.refreshTimeout);
      this.refreshTimeout = undefined;
    }
    if (!this.testService?.isReady()) {
      return;
    }
    this.refreshTimeout = setTimeout(() => {
      this.refreshTimeout = undefined;
      void this.discoverTests();
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
    if (!this.canRunTests()) {
      return;
    }
    if (!(await this.discoverTests())) {
      return;
    }
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
    if (!this.canRunTests()) {
      return;
    }

    // Ensure tests are discovered for this file
    if (!(await this.discoverTests())) {
      return;
    }

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
  private async discoverTests(): Promise<boolean> {
    if (this.disposed || !this.testService?.isReady()) {
      return false;
    }

    const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
    if (!workspaceFolder) {
      return false;
    }

    // Immediate discovery supersedes a queued refresh, which would invalidate it.
    if (this.refreshTimeout) {
      clearTimeout(this.refreshTimeout);
      this.refreshTimeout = undefined;
    }
    const discoveryVersion = ++this.discoveryVersion;
    try {
      const suites = await this.testService.discoverTestsInWorkspace(
        workspaceFolder.uri.toString(),
      );
      if (
        this.disposed ||
        discoveryVersion !== this.discoveryVersion ||
        !this.testService.isReady()
      ) {
        return false;
      }

      // Clear existing items
      this.ctrl.items.replace([]);

      // Map suites to TestItems
      for (const suite of suites) {
        const suiteItem = this.createSuiteItem(suite);
        this.ctrl.items.add(suiteItem);
      }
      return true;
    } catch (error) {
      console.error("Failed to discover tests:", error);
      return false;
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

    // Add child test items
    for (const test of suite.tests) {
      const testItem = this.createTestItem(test, suite.suite, uri);
      suiteItem.children.add(testItem);
    }

    // Set suite range using the actual class line from the LSP
    // suite.line is 1-indexed, VS Code positions are 0-indexed
    const classLine = suite.line >= 1 ? suite.line - 1 : 0;
    const APPROXIMATE_LINE_LENGTH = 100;
    suiteItem.range = new vscode.Range(
      new vscode.Position(classLine, 0),
      new vscode.Position(classLine, APPROXIMATE_LINE_LENGTH),
    );

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

  private canRunTests(): boolean {
    if (this.disposed) {
      return false;
    }
    if (this.testService && !this.testService.isReady()) {
      vscode.window.showErrorMessage("Groovy Language Server is not running");
      return false;
    }
    return true;
  }

  // Unified command handler
  private async runTestCommand(
    input: unknown,
    debug: boolean,
    withCoverage: boolean = false,
  ) {
    if (input === undefined) {
      const action = withCoverage
        ? "Run with Coverage"
        : debug
          ? "Debug"
          : "Run";
      const codeLens = withCoverage ? "Coverage" : action;
      vscode.window.showInformationMessage(
        `Select a test or suite in Test Explorer and choose ${action}, or use its ${codeLens} CodeLens.`,
      );
      return;
    }
    const decoded = decodeTestCommandArgs(input);
    if ("error" in decoded) {
      vscode.window.showErrorMessage(decoded.error);
      return;
    }
    const args = decoded.args;
    const isWildcard = args.test === "*";

    // Check if file is outside workspace - cannot run tests from external projects
    // TODO(#714): Support external file test execution by detecting their project root
    if (args.uri && !this.isInWorkspace(args.uri)) {
      vscode.window.showWarningMessage(
        "Cannot run test: file is outside the current workspace. " +
          "Open the file's project folder to run its tests.",
      );
      return;
    }
    if (!this.canRunTests()) {
      return;
    }

    const suiteItem = this.ctrl.items.get(args.suite);
    if (suiteItem && !this.validateTestItemUri(suiteItem, args.uri)) {
      return;
    }
    if (isWildcard && suiteItem) {
      await this.runTestItem(suiteItem, debug, withCoverage, args.uri);
      return;
    }

    let item = isWildcard
      ? undefined
      : this.findTestItem(args.suite, args.test);

    if (!item) {
      // If item not found, it might be because tests weren't discovered yet.
      // Try discovery first.
      if (!(await this.discoverTests())) {
        return;
      }

      const retrySuiteItem = this.ctrl.items.get(args.suite);
      if (
        retrySuiteItem &&
        !this.validateTestItemUri(retrySuiteItem, args.uri)
      ) {
        return;
      }
      if (isWildcard) {
        if (retrySuiteItem) {
          await this.runTestItem(retrySuiteItem, debug, withCoverage, args.uri);
          return;
        }
      } else {
        const retryItem = this.findTestItem(args.suite, args.test);
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
      await this.runTestItem(item, debug, withCoverage, args.uri);
    }
  }

  private validateTestItemUri(item: vscode.TestItem, uri?: string): boolean {
    if (uri !== undefined && item.uri?.toString() !== uri) {
      vscode.window.showErrorMessage(
        "Cannot run test: the requested URI does not match the discovered test. Refresh tests and try again.",
      );
      return false;
    }
    return true;
  }

  private async runTestItem(
    item: vscode.TestItem,
    debug: boolean,
    withCoverage: boolean = false,
    uri?: string,
  ) {
    if (!this.validateTestItemUri(item, uri)) {
      return;
    }
    if (
      withCoverage &&
      (!this.executionService.runTestsWithCoverage || !this.coverageService)
    ) {
      vscode.window.showErrorMessage(
        "Coverage execution is unavailable for this test runner",
      );
      return;
    }
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

  private findTestItem(
    suiteName: string,
    testName: string,
  ): vscode.TestItem | undefined {
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
   * Create a missing test item for a validated command target without replacing
   * another discovery's suite or reusing a child from a different URI.
   */
  private createOnTheFlyTestItem(
    uriString: string,
    suiteName: string,
    testName: string,
  ): vscode.TestItem | undefined {
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
    if (suiteItem && !this.validateTestItemUri(suiteItem, uri.toString())) {
      return;
    }
    if (!suiteItem) {
      suiteItem = this.ctrl.createTestItem(
        suiteName,
        this.getClassName(suiteName),
        uri,
      );
      this.ctrl.items.add(suiteItem);
    }

    // Check if test item already exists under the suite
    const testId = `${suiteName}.${testName}`;
    let testItem = suiteItem.children.get(testId);
    if (testItem && !this.validateTestItemUri(testItem, uri.toString())) {
      return;
    }
    if (!testItem) {
      testItem = this.ctrl.createTestItem(testId, testName, uri);
      // We don't have line information for on-the-fly items, so skip setting range
      suiteItem.children.add(testItem);
    }

    return testItem;
  }
}
