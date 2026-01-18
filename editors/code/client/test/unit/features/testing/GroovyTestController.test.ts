import * as assert from "assert";
import * as sinon from "sinon";
import proxyquire from "proxyquire";

interface GroovyTestControllerType {
  new (context: unknown, executionService: unknown, testService: unknown): { dispose: () => void };
}

interface ExecutionServiceMock {
  runTests: sinon.SinonStub;
}

interface TestServiceMock {
  discoverTestsInWorkspace: sinon.SinonStub;
}

interface VscodeMock {
  tests: {
    createTestController: sinon.SinonStub;
  };
  TestTag: new (id: string) => { id: string };
  commands: {
    registerCommand: sinon.SinonStub;
  };
  window: {
    showErrorMessage: sinon.SinonStub;
    showWarningMessage: sinon.SinonStub;
    visibleTextEditors: unknown[];
  };
  workspace: {
    workspaceFolders: { uri: { toString: () => string }; name: string; index: number }[];
    getWorkspaceFolder: sinon.SinonStub;
  };
  Range: new (start: unknown, end: unknown) => { start: unknown; end: unknown };
  Position: new (line: number, character: number) => { line: number; character: number };
  Uri: { parse: (str: string) => { toString: () => string } };
  TestRunProfileKind: { Run: number; Debug: number };
}

interface TestControllerMock {
  createTestItem: sinon.SinonStub;
  items: {
    add: sinon.SinonStub;
    get: sinon.SinonStub;
    delete: sinon.SinonStub;
    replace: sinon.SinonStub;
  };
  createRunProfile: sinon.SinonStub;
  resolveHandler: unknown;
  refreshHandler: unknown;
}

describe("GroovyTestController", () => {
  let GroovyTestController: GroovyTestControllerType;
  let contextMock: { subscriptions: { push: sinon.SinonStub } };
  let executionServiceMock: ExecutionServiceMock;
  let testServiceMock: TestServiceMock;
  let vscodeMock: VscodeMock;
  let testControllerMock: TestControllerMock;
  let sandbox: sinon.SinonSandbox;

  beforeEach(() => {
    sandbox = sinon.createSandbox();

    // Mock test items
    const testItemsMock = new Map();

    // Mock TestController
    testControllerMock = {
      createTestItem: sandbox
        .stub()
        .callsFake((id: string, label: string, uri?: unknown) => {
          const childrenMap = new Map();
          return {
            id,
            label,
            uri,
            range: undefined,
            children: {
              add: sandbox.stub().callsFake((item: { id: string }) => {
                childrenMap.set(item.id, item);
              }),
              get: sandbox
                .stub()
                .callsFake((childId: string) => childrenMap.get(childId)),
              replace: sandbox.stub().callsFake(() => childrenMap.clear()),
            },
          };
        }),
      items: {
        add: sandbox.stub().callsFake((item: { id: string }) => {
          testItemsMock.set(item.id, item);
        }),
        get: sandbox.stub().callsFake((id: string) => testItemsMock.get(id)),
        delete: sandbox.stub().callsFake((id: string) => {
          testItemsMock.delete(id);
        }),
        replace: sandbox.stub().callsFake(() => {
          testItemsMock.clear();
        }),
      },
      createRunProfile: sandbox.stub(),
      resolveHandler: undefined,
      refreshHandler: undefined,
    };

    // Mock VS Code API
    vscodeMock = {
      tests: {
        createTestController: sandbox.stub().returns(testControllerMock),
      },
      TestTag: class TestTag {
        constructor(public id: string) {}
      },
      commands: {
        registerCommand: sandbox
          .stub()
          .callsFake((_command: string, _handler: unknown) => {
            // Store the handler for testing
            return { dispose: sandbox.stub() };
          }),
      },
      window: {
        showErrorMessage: sandbox.stub(),
        showWarningMessage: sandbox.stub(),
        visibleTextEditors: [],
      },
      workspace: {
        workspaceFolders: [
          {
            uri: { toString: () => "file:///workspace" },
            name: "test-workspace",
            index: 0,
          },
        ],
        // Mock getWorkspaceFolder to return undefined for external URIs
        getWorkspaceFolder: sandbox.stub().callsFake((uri: { toString: () => string }) => {
          const uriStr = uri.toString();
          if (uriStr.startsWith("file:///workspace")) {
            return {
              uri: { toString: () => "file:///workspace" },
              name: "test-workspace",
              index: 0,
            };
          }
          return undefined; // External file
        }),
        createFileSystemWatcher: sandbox.stub().returns({
          onDidCreate: sandbox.stub().returns({ dispose: sandbox.stub() }),
          onDidChange: sandbox.stub().returns({ dispose: sandbox.stub() }),
          onDidDelete: sandbox.stub().returns({ dispose: sandbox.stub() }),
          dispose: sandbox.stub(),
        }),
        onDidOpenTextDocument: sandbox
          .stub()
          .returns({ dispose: sandbox.stub() }),
        onDidSaveTextDocument: sandbox
          .stub()
          .returns({ dispose: sandbox.stub() }),
      },
      TestRunProfileKind: {
        Run: 1,
        Debug: 2,
        Coverage: 3,
      },
      Uri: {
        parse: sandbox.stub().callsFake((uri: string) => ({
          toString: () => uri,
          fsPath: uri.replace("file://", ""),
        })),
      },
      Range: class {
        constructor(
          public start: unknown,
          public end: unknown,
        ) {}
      },
      Position: class {
        constructor(
          public line: number,
          public character: number,
        ) {}
      },
      TestRunRequest: class {
        constructor(public include: unknown[]) {}
      },
      CancellationTokenSource: class {
        token = {};
        dispose = sandbox.stub();
      },
    };

    // Mock context
    contextMock = {
      subscriptions: {
        push: sandbox.stub(),
      },
    };

    // Mock execution service
    executionServiceMock = {
      runTests: sandbox.stub().resolves(),
      debugTests: sandbox.stub().resolves(),
    };

    // Mock test service
    testServiceMock = {
      discoverTestsInWorkspace: sandbox.stub().resolves([]),
    };

    // Load GroovyTestController with mocks
    const module = (proxyquire as { noCallThru: () => (path: string, stubs: unknown) => { GroovyTestController: GroovyTestControllerType } }).noCallThru()(
      "../../../../src/features/testing/GroovyTestController",
      {
        vscode: vscodeMock,
      },
    );
    GroovyTestController = module.GroovyTestController;
  });

  afterEach(() => {
    sandbox.restore();
  });

  describe("runTestCommand with external files", () => {
    let _controller: { dispose: () => void };

    it("should show warning for external file and not run test", async () => {
      // Arrange
      _controller = new GroovyTestController(
        contextMock,
        executionServiceMock,
        testServiceMock,
      );

      const externalUri =
        "file:///Users/adsc/dev/refs/jenkins-spock/ExternalSpec.groovy";
      const suiteName = "com.example.ExternalSpec";
      const testName = "should work from external file";
      const args = {
        uri: externalUri,
        suite: suiteName,
        test: testName,
      };

      // Get the registered command handler
      const registerCommandCalls =
        vscodeMock.commands.registerCommand.getCalls();
      const runTestCall = registerCommandCalls.find(
        (call: unknown) => call.args[0] === "groovy.test.run",
      );
      assert.ok(runTestCall, "groovy.test.run command should be registered");
      const runTestHandler = runTestCall.args[1];

      // Act
      await runTestHandler(args);

      // Assert
      // Should show warning message for external file (TODO #714)
      assert.ok(
        vscodeMock.window.showWarningMessage.calledOnce,
        "Should show warning message for external file",
      );

      // Should NOT run tests for external files
      assert.ok(
        executionServiceMock.runTests.notCalled,
        "Should not attempt to run tests for external file",
      );
    });

    it("should use existing test item if found in workspace", async () => {
      // Arrange
      _controller = new GroovyTestController(
        contextMock,
        executionServiceMock,
        testServiceMock,
      );

      const workspaceUri =
        "file:///workspace/src/test/groovy/WorkspaceSpec.groovy";
      const suiteName = "com.example.WorkspaceSpec";
      const testName = "should work from workspace";

      // Pre-populate the test tree with a workspace test
      testServiceMock.discoverTestsInWorkspace.resolves([
        {
          uri: workspaceUri,
          suite: suiteName,
          line: 5,
          tests: [{ test: testName, line: 10 }],
        },
      ]);

      // Manually trigger discovery
      if (testControllerMock.resolveHandler) {
        await testControllerMock.resolveHandler(undefined);
      }

      const args = {
        uri: workspaceUri,
        suite: suiteName,
        test: testName,
      };

      // Get the registered command handler
      const registerCommandCalls =
        vscodeMock.commands.registerCommand.getCalls();
      const runTestCall = registerCommandCalls.find(
        (call: unknown) => call.args[0] === "groovy.test.run",
      );
      const runTestHandler = runTestCall.args[1];

      // Act
      await runTestHandler(args);

      // Assert
      assert.ok(
        vscodeMock.window.showErrorMessage.notCalled,
        "Should not show error message when workspace test is found",
      );

      assert.ok(
        executionServiceMock.runTests.calledOnce,
        "Should call runTests once",
      );
    });

    it("should handle missing suite name gracefully", async () => {
      // Arrange
      _controller = new GroovyTestController(
        contextMock,
        executionServiceMock,
        testServiceMock,
      );

      const externalUri =
        "file:///Users/adsc/dev/refs/jenkins-spock/ExternalSpec.groovy";
      const args = {
        uri: externalUri,
        suite: "",
        test: "should work",
      };

      // Get the registered command handler
      const registerCommandCalls =
        vscodeMock.commands.registerCommand.getCalls();
      const runTestCall = registerCommandCalls.find(
        (call: unknown) => call.args[0] === "groovy.test.run",
      );
      const runTestHandler = runTestCall.args[1];

      // Act
      await runTestHandler(args);

      // Assert
      assert.ok(
        vscodeMock.window.showErrorMessage.calledOnce,
        "Should show error message for empty suite name",
      );
      assert.ok(
        executionServiceMock.runTests.notCalled,
        "Should not attempt to run tests with empty suite name",
      );
    });

    it("should preserve test suites when external file triggers warning", async () => {
      // Ensure external file warning doesn't affect existing workspace test suites
      // Arrange
      _controller = new GroovyTestController(
        contextMock,
        executionServiceMock,
        testServiceMock,
      );

      const workspaceUri = "file:///workspace/TestA.groovy";
      const externalUri = "file:///external/TestA.groovy";
      const suiteAName = "com.example.TestA";
      const suiteBName = "com.example.TestB";
      const workspaceTestName = "workspace test method";
      const externalTestName = "external test method";

      // Pre-populate test tree with two workspace suites
      testServiceMock.discoverTestsInWorkspace.resolves([
        {
          uri: workspaceUri,
          suite: suiteAName,
          line: 5,
          tests: [{ test: workspaceTestName, line: 10 }],
        },
        {
          uri: "file:///workspace/TestB.groovy",
          suite: suiteBName,
          line: 8,
          tests: [{ test: "other test", line: 20 }],
        },
      ]);

      // Trigger discovery to populate both suites
      if (testControllerMock.resolveHandler) {
        await testControllerMock.resolveHandler(undefined);
      }

      // Verify both suites exist before attempting external test
      const suiteABefore = testControllerMock.items.get(suiteAName);
      const suiteBBefore = testControllerMock.items.get(suiteBName);
      assert.ok(
        suiteABefore,
        "Suite A should exist before external test attempt",
      );
      assert.ok(
        suiteBBefore,
        "Suite B should exist before external test attempt",
      );
      assert.strictEqual(
        suiteABefore.uri.toString(),
        workspaceUri,
        "Suite A should have workspace URI",
      );

      // Try to run test from external file
      const args = {
        uri: externalUri,
        suite: suiteAName,
        test: externalTestName,
      };

      const registerCommandCalls =
        vscodeMock.commands.registerCommand.getCalls();
      const runTestCall = registerCommandCalls.find(
        (call: unknown) => call.args[0] === "groovy.test.run",
      );
      const runTestHandler = runTestCall.args[1];

      // Act
      await runTestHandler(args);

      // Assert - Should show warning for external file
      assert.ok(
        vscodeMock.window.showWarningMessage.calledOnce,
        "Should show warning for external file",
      );

      // Both suites should still exist (warning should not modify test tree)
      const suiteAAfter = testControllerMock.items.get(suiteAName);
      const suiteBAfter = testControllerMock.items.get(suiteBName);
      assert.ok(
        suiteAAfter,
        "Suite A should still exist after external file warning",
      );
      assert.ok(
        suiteBAfter,
        "Suite B should still exist after external file warning",
      );

      // Suite A should retain original workspace URI
      assert.strictEqual(
        suiteAAfter.uri.toString(),
        workspaceUri,
        "Suite A URI should remain unchanged",
      );

      // Should NOT have run any tests
      assert.ok(
        executionServiceMock.runTests.notCalled,
        "Should not run tests for external file",
      );
    });
  });

  describe("runTestCommand with wildcard", () => {
    let _controller: { dispose: () => void };

    it('should run entire suite when test is "*" (wildcard)', async () => {
      // Arrange: Set up a suite with tests discovered
      const suiteUri = "file:///workspace/MySpec.groovy";
      const suiteName = "com.example.MySpec";
      testServiceMock.discoverTestsInWorkspace.resolves([
        {
          uri: suiteUri,
          suite: suiteName,
          line: 3,
          tests: [
            { test: "test one", line: 10 },
            { test: "test two", line: 20 },
          ],
        },
      ]);

      _controller = new GroovyTestController(
        contextMock,
        executionServiceMock,
        testServiceMock,
      );

      // Trigger discovery
      await testControllerMock.resolveHandler(undefined);

      // Reset the mock to track only the run call
      executionServiceMock.runTests.resetHistory();

      // Act: Run with wildcard (simulating CodeLens "Run All Tests" click)
      const runCommand = vscodeMock.commands.registerCommand.args.find(
        (args: unknown[]) => args[0] === "groovy.test.run",
      );
      assert.ok(runCommand, "groovy.test.run command should be registered");

      if (runCommand && runCommand[1]) {
        await runCommand[1]({ suite: suiteName, test: "*", uri: suiteUri });
      }

      // Assert: Should run without errors
      assert.ok(
        !vscodeMock.window.showErrorMessage.called,
        "Should not show error for wildcard test",
      );

      // STRICT ASSERTION: Should run the SUITE item, not a wildcard item
      assert.ok(
        executionServiceMock.runTests.calledOnce,
        "Should call runTests",
      );

      const runRequest = executionServiceMock.runTests.firstCall.args[0];
      const runItem = runRequest.include[0];

      // This assertion ensures we are running the actual suite item found in the tree
      // and NOT creating a new on-the-fly item with id "suite.*"
      assert.strictEqual(
        runItem.id,
        suiteName,
        `Should run the suite item (id=${suiteName}), but ran item with id=${runItem.id}`,
      );
    });

    it("should not show error message for valid test names", async () => {
      // Arrange
      const suiteUri = "file:///workspace/MySpec.groovy";
      const suiteName = "com.example.MySpec";
      const testName = "test one";
      testServiceMock.discoverTestsInWorkspace.resolves([
        {
          uri: suiteUri,
          suite: suiteName,
          line: 3,
          tests: [{ test: testName, line: 10 }],
        },
      ]);

      _controller = new GroovyTestController(
        contextMock,
        executionServiceMock,
        testServiceMock,
      );

      // Trigger discovery
      await testControllerMock.resolveHandler(undefined);

      // Act: Run specific test
      const runCommand = vscodeMock.commands.registerCommand.args.find(
        (args: unknown[]) => args[0] === "groovy.test.run",
      );
      if (runCommand && runCommand[1]) {
        await runCommand[1]({
          suite: suiteName,
          test: testName,
          uri: suiteUri,
        });
      }

      // Assert
      assert.ok(
        !vscodeMock.window.showErrorMessage.called,
        "Should not show error for valid test name",
      );
    });
  });

  describe("suite range positioning (gutter icon location)", () => {
    let _controller: { dispose: () => void };

    it("should position suite gutter icon at actual class line, not estimated from test methods", async () => {
      // Regression test for gutter icon positioning bug
      // The class is at line 8, but the first test method is at line 19
      // The suite gutter icon should be at line 8 (0-indexed: 7), not line 14 (19 - 5)
      const suiteUri = "file:///workspace/PublishReportsStepTests.groovy";
      const suiteName = "PublishReportsStepTests";
      const classLine = 8; // Class declared at line 8
      const firstTestLine = 19; // First test method at line 19

      testServiceMock.discoverTestsInWorkspace.resolves([
        {
          uri: suiteUri,
          suite: suiteName,
          line: classLine, // LSP now provides actual class line
          tests: [
            { test: "test_without_trusted_infra", line: firstTestLine },
            { test: "test_with_trusted_infra", line: 35 },
          ],
        },
      ]);

      _controller = new GroovyTestController(
        contextMock,
        executionServiceMock,
        testServiceMock,
      );

      // Trigger discovery
      await testControllerMock.resolveHandler(undefined);

      // Get the suite item
      const suiteItem = testControllerMock.items.get(suiteName);
      assert.ok(suiteItem, "Suite item should be created");

      // Verify the suite range uses the actual class line (1-indexed to 0-indexed)
      // Line 8 (1-indexed) should become Position(7, 0) (0-indexed)
      const expectedLine = classLine - 1; // Convert 1-indexed to 0-indexed
      assert.strictEqual(
        suiteItem.range.start.line,
        expectedLine,
        `Suite range should start at line ${expectedLine} (0-indexed from class line ${classLine}), ` +
          `not ${suiteItem.range.start.line}`,
      );
    });

    it("should position test method gutter icon at correct line", async () => {
      const suiteUri = "file:///workspace/MySpec.groovy";
      const suiteName = "com.example.MySpec";
      const testMethodLine = 15;

      testServiceMock.discoverTestsInWorkspace.resolves([
        {
          uri: suiteUri,
          suite: suiteName,
          line: 3,
          tests: [{ test: "should work", line: testMethodLine }],
        },
      ]);

      _controller = new GroovyTestController(
        contextMock,
        executionServiceMock,
        testServiceMock,
      );

      // Trigger discovery
      await testControllerMock.resolveHandler(undefined);

      // Get the test item
      const suiteItem = testControllerMock.items.get(suiteName);
      const testItem = suiteItem.children.get(`${suiteName}.should work`);

      assert.ok(testItem, "Test item should be created");

      // Test line should be converted from 1-indexed to 0-indexed
      const expectedLine = testMethodLine - 1;
      assert.strictEqual(
        testItem.range.start.line,
        expectedLine,
        `Test range should start at line ${expectedLine} (0-indexed from test line ${testMethodLine})`,
      );
    });
  });
});
