import * as assert from "assert";
import * as sinon from "sinon";
import proxyquire from "proxyquire";

describe("LSPTestExecutionService", () => {
  let LSPTestExecutionService: any;
  let service: any;
  let testServiceMock: any;
  let loggerMock: any;
  let sandbox: sinon.SinonSandbox;

  let vscodeMock: any;
  let cpMock: any;
  let fsMock: any;
  let readlineMock: any;
  let pathMock: any;

  beforeEach(() => {
    sandbox = sinon.createSandbox();

    // Mock logger
    loggerMock = {
      appendLine: sandbox.stub(),
    };

    // Mock test service
    testServiceMock = {
      getTestCommand: sandbox.stub(),
      getTestResults: sandbox.stub(),
    };

    // Mock vscode
    vscodeMock = {
      workspace: {
        workspaceFolders: [{ uri: { toString: () => "file:///workspace" } }],
        getConfiguration: sandbox.stub().returns({
          get: sandbox.stub().returns(undefined),
        }),
      },
      window: {
        showErrorMessage: sandbox.stub().resolves(),
        showWarningMessage: sandbox.stub(),
      },
      TestRunRequest: class {
        constructor(public include: any[]) {}
      },
      CancellationTokenSource: class {
        token = {
          isCancellationRequested: false,
          onCancellationRequested: () => ({ dispose: () => {} }),
        };
        dispose = sandbox.stub();
      },
      TestMessage: class {
        constructor(public message: string) {}
      },
      Location: class {
        constructor(
          public uri: any,
          public range: any,
        ) {}
      },
      Uri: {
        parse: (s: string) => ({ toString: () => s, fsPath: s }),
      },
      Position: class {
        constructor(
          public line: number,
          public character: number,
        ) {}
      },
      Range: class {
        constructor(
          public start: any,
          public end: any,
        ) {}
      },
      TestRunProfileKind: { Run: 1, Coverage: 3 },
    };

    // Mock child_process
    cpMock = {
      spawn: sandbox.stub(),
    };

    // Mock fs
    fsMock = {
      existsSync: sandbox.stub().returns(true),
      statSync: sandbox.stub().returns({ isFile: () => false }),
    };

    // Mock readline
    readlineMock = {
      createInterface: sandbox.stub().returns({
        on: sandbox.stub(),
        close: sandbox.stub(),
      }),
    };

    // Mock path
    pathMock = {
      join: (...args: string[]) => args.join("/"),
      basename: (p: string) => {
        const parts = p.split("/");
        return parts[parts.length - 1];
      },
      delimiter: ":",
    };

    const module = (proxyquire as any).noCallThru()(
      "../../../../src/features/testing/LSPTestExecutionService",
      {
        vscode: vscodeMock,
        child_process: cpMock,
        fs: fsMock,
        readline: readlineMock,
        path: pathMock,
        "./TestEventConsumer": (proxyquire as any).noCallThru()(
          "../../../../src/features/testing/TestEventConsumer",
          { vscode: vscodeMock },
        ),
      },
    );
    LSPTestExecutionService = module.LSPTestExecutionService;
    service = new LSPTestExecutionService(
      testServiceMock,
      loggerMock,
      "/extension/path",
    );
  });

  afterEach(() => {
    sandbox.restore();
  });

  describe("runTestsWithCoverage", () => {
    let testControllerMock: any;
    let testRunMock: any;
    let coverageServiceMock: any;
    let tokenMock: any;

    beforeEach(() => {
      testRunMock = {
        enqueued: sandbox.stub(),
        passed: sandbox.stub(),
        failed: sandbox.stub(),
        skipped: sandbox.stub(),
        errored: sandbox.stub(),
        appendOutput: sandbox.stub(),
        end: sandbox.stub(),
      };

      testControllerMock = {
        createTestRun: sandbox.stub().returns(testRunMock),
      };

      coverageServiceMock = {
        addCoverageToRun: sandbox.stub().resolves(),
      };

      tokenMock = {
        isCancellationRequested: false,
        onCancellationRequested: sandbox
          .stub()
          .returns({ dispose: sandbox.stub() }),
      };

      // Setup default spawn mock
      const stdout = { on: sandbox.stub() };
      const stderr = { on: sandbox.stub() };
      const proc = {
        stdout,
        stderr,
        on: sandbox.stub().callsFake((event: string, callback: any) => {
          if (event === "close") {
            setTimeout(() => callback(0), 0);
          }
        }),
        kill: sandbox.stub(),
      };
      cpMock.spawn.returns(proc);
    });

    it("should append jacocoTestReport for Gradle projects", async () => {
      const testItem = {
        id: "com.example.MySpec",
        uri: { toString: () => "file:///test.groovy" },
        children: { size: 0 },
      };

      const request = { include: [testItem] };

      testServiceMock.getTestCommand.resolves({
        executable: "/path/to/gradlew",
        args: ["test", "--tests", "com.example.MySpec"],
        cwd: "/workspace",
        env: {},
      });

      await service.runTestsWithCoverage(
        request,
        tokenMock,
        testControllerMock,
        coverageServiceMock,
      );

      // Verify spawn was called with jacocoTestReport appended
      assert.ok(cpMock.spawn.calledOnce);
      const spawnArgs = cpMock.spawn.getCall(0).args;
      const args = spawnArgs[1];
      assert.ok(
        args.includes("jacocoTestReport"),
        "Should append jacocoTestReport for Gradle",
      );
    });

    it("should append jacoco:report for Maven projects", async () => {
      const testItem = {
        id: "com.example.MyTest",
        uri: { toString: () => "file:///test.groovy" },
        children: { size: 0 },
      };

      const request = { include: [testItem] };

      testServiceMock.getTestCommand.resolves({
        executable: "/path/to/mvnw",
        args: ["test", "-Dtest=com.example.MyTest"],
        cwd: "/workspace",
        env: {},
      });

      testServiceMock.getTestResults.resolves({ results: [] });

      await service.runTestsWithCoverage(
        request,
        tokenMock,
        testControllerMock,
        coverageServiceMock,
      );

      // Verify spawn was called with jacoco:report appended
      assert.ok(cpMock.spawn.calledOnce);
      const spawnArgs = cpMock.spawn.getCall(0).args;
      const args = spawnArgs[1];
      assert.ok(
        args.includes("jacoco:report"),
        "Should append jacoco:report for Maven",
      );
    });

    it("should call coverage service after test execution", async () => {
      const testItem = {
        id: "com.example.MySpec",
        uri: { toString: () => "file:///test.groovy" },
        children: { size: 0 },
      };

      const request = { include: [testItem] };

      testServiceMock.getTestCommand.resolves({
        executable: "/path/to/gradlew",
        args: ["test", "--tests", "com.example.MySpec"],
        cwd: "/workspace",
        env: {},
      });

      await service.runTestsWithCoverage(
        request,
        tokenMock,
        testControllerMock,
        coverageServiceMock,
      );

      // Verify coverage service was called
      assert.ok(
        coverageServiceMock.addCoverageToRun.calledOnce,
        "Should call addCoverageToRun",
      );
      assert.ok(
        coverageServiceMock.addCoverageToRun.calledWith(
          testRunMock,
          "file:///workspace",
        ),
        "Should pass correct arguments to coverage service",
      );
    });

    it("should handle suite-level execution (dot in ID from package)", async () => {
      const testItem = {
        id: "com.example.MySpec",
        uri: { toString: () => "file:///test.groovy" },
        children: { size: 0 },
      };

      const request = { include: [testItem] };

      testServiceMock.getTestCommand.resolves({
        executable: "/path/to/gradlew",
        args: ["test"],
        cwd: "/workspace",
        env: {},
      });

      await service.runTestsWithCoverage(
        request,
        tokenMock,
        testControllerMock,
        coverageServiceMock,
      );

      // Note: With the dot-based heuristic, "com.example.MySpec" is treated as "com.example" suite + "MySpec" test
      // This is a known limitation - the heuristic works for simple class names but not fully qualified ones
      // In practice, TestItems created by GroovyTestController will have the correct structure
      assert.ok(testServiceMock.getTestCommand.calledOnce);
      const callArgs = testServiceMock.getTestCommand.getCall(0).args;
      assert.strictEqual(
        callArgs[1],
        "com.example",
        "Should extract suite name before last dot",
      );
      assert.strictEqual(
        callArgs[2],
        "MySpec",
        "Should extract test name after last dot",
      );
    });

    it("should handle test-level execution (dot in ID)", async () => {
      const testItem = {
        id: "com.example.MySpec.testMethod",
        uri: { toString: () => "file:///test.groovy" },
        children: { size: 0 },
      };

      const request = { include: [testItem] };

      testServiceMock.getTestCommand.resolves({
        executable: "/path/to/gradlew",
        args: ["test"],
        cwd: "/workspace",
        env: {},
      });

      await service.runTestsWithCoverage(
        request,
        tokenMock,
        testControllerMock,
        coverageServiceMock,
      );

      // Verify getTestCommand was called with suite and test names
      assert.ok(testServiceMock.getTestCommand.calledOnce);
      const callArgs = testServiceMock.getTestCommand.getCall(0).args;
      assert.strictEqual(
        callArgs[1],
        "com.example.MySpec",
        "Should extract suite name",
      );
      assert.strictEqual(callArgs[2], "testMethod", "Should extract test name");
    });

    it("should not mutate original command args", async () => {
      const testItem = {
        id: "com.example.MySpec",
        uri: { toString: () => "file:///test.groovy" },
        children: { size: 0 },
      };

      const request = { include: [testItem] };

      const originalArgs = ["test", "--tests", "com.example.MySpec"];
      const commandArgs = [...originalArgs];

      testServiceMock.getTestCommand.resolves({
        executable: "/path/to/gradlew",
        args: commandArgs,
        cwd: "/workspace",
        env: {},
      });

      await service.runTestsWithCoverage(
        request,
        tokenMock,
        testControllerMock,
        coverageServiceMock,
      );

      // Verify original args were not modified
      assert.deepStrictEqual(
        commandArgs,
        originalArgs,
        "Should not mutate original command args",
      );
    });

    it("should use path.basename for build tool detection", async () => {
      const testCases = [
        { executable: "/usr/local/bin/gradle", expectGradle: true },
        {
          executable: "/home/user/.sdkman/candidates/gradle/8.0/bin/gradle",
          expectGradle: true,
        },
        { executable: "./gradlew", expectGradle: true },
        { executable: "/usr/bin/mvn", expectMaven: true },
        { executable: "./mvnw", expectMaven: true },
        { executable: "/path/to/mvn.cmd", expectMaven: true },
      ];

      for (const testCase of testCases) {
        sandbox.resetHistory();

        const testItem = {
          id: "com.example.Test",
          uri: { toString: () => "file:///test.groovy" },
          children: { size: 0 },
        };

        const request = { include: [testItem] };

        testServiceMock.getTestCommand.resolves({
          executable: testCase.executable,
          args: ["test"],
          cwd: "/workspace",
          env: {},
        });

        if (testCase.expectMaven) {
          testServiceMock.getTestResults.resolves({ results: [] });
        }

        await service.runTestsWithCoverage(
          request,
          tokenMock,
          testControllerMock,
          coverageServiceMock,
        );

        const spawnArgs = cpMock.spawn.getCall(0).args;
        const args = spawnArgs[1];

        if (testCase.expectGradle) {
          assert.ok(
            args.includes("jacocoTestReport"),
            `Should detect Gradle for ${testCase.executable}`,
          );
        } else if (testCase.expectMaven) {
          assert.ok(
            args.includes("jacoco:report"),
            `Should detect Maven for ${testCase.executable}`,
          );
        }
      }
    });
  });

  describe("runTests", () => {
    let testControllerMock: any;
    let testRunMock: any;
    let tokenMock: any;

    beforeEach(() => {
      testRunMock = {
        enqueued: sandbox.stub(),
        passed: sandbox.stub(),
        failed: sandbox.stub(),
        skipped: sandbox.stub(),
        errored: sandbox.stub(),
        appendOutput: sandbox.stub(),
        end: sandbox.stub(),
      };

      testControllerMock = {
        createTestRun: sandbox.stub().returns(testRunMock),
      };

      tokenMock = {
        isCancellationRequested: false,
        onCancellationRequested: sandbox
          .stub()
          .returns({ dispose: sandbox.stub() }),
      };

      // Setup default spawn mock
      const stdout = { on: sandbox.stub() };
      const stderr = { on: sandbox.stub() };
      const proc = {
        stdout,
        stderr,
        on: sandbox.stub().callsFake((event: string, callback: any) => {
          if (event === "close") {
            setTimeout(() => callback(0), 0);
          }
        }),
        kill: sandbox.stub(),
      };
      cpMock.spawn.returns(proc);
    });

    it("should not append coverage tasks when running without coverage", async () => {
      const testItem = {
        id: "com.example.MySpec",
        uri: { toString: () => "file:///test.groovy" },
        children: { size: 0 },
      };

      const request = { include: [testItem] };

      testServiceMock.getTestCommand.resolves({
        executable: "/path/to/gradlew",
        args: ["test", "--tests", "com.example.MySpec"],
        cwd: "/workspace",
        env: {},
      });

      await service.runTests(request, tokenMock, testControllerMock);

      // Verify spawn was called WITHOUT jacocoTestReport
      assert.ok(cpMock.spawn.calledOnce);
      const spawnArgs = cpMock.spawn.getCall(0).args;
      const args = spawnArgs[1];
      assert.ok(
        !args.includes("jacocoTestReport"),
        "Should not append jacocoTestReport for regular test run",
      );
      assert.ok(
        !args.includes("jacoco:report"),
        "Should not append jacoco:report for regular test run",
      );
    });
  });
});
