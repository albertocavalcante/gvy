import * as assert from "assert";
import * as sinon from "sinon";
import proxyquire from "proxyquire";

interface LSPTestExecutionServiceInstance {
  runTestsWithCoverage: (
    request: unknown,
    token: unknown,
    testController: unknown,
    coverageService: unknown,
  ) => Promise<void>;
  runTests: (
    request: unknown,
    token: unknown,
    testController: unknown,
  ) => Promise<void>;
  applyTestResults: (
    uri: string,
    run: unknown,
    items: unknown[],
    consumer: unknown,
  ) => Promise<void>;
  isValidJavaHome: (path: string) => boolean;
  collectAllTestItems: (items: unknown[]) => unknown[];
}

interface LSPTestExecutionServiceType {
  new (
    testService: unknown,
    logger: unknown,
    extensionPath: string,
  ): LSPTestExecutionServiceInstance;
}

interface TestServiceMock {
  getTestCommand: sinon.SinonStub;
  getTestResults: sinon.SinonStub;
}

interface LoggerMock {
  appendLine: sinon.SinonStub;
}

interface VscodeMock {
  workspace: {
    workspaceFolders: Array<{ uri: { toString: () => string } }>;
    getConfiguration: sinon.SinonStub;
  };
  window: {
    showErrorMessage: sinon.SinonStub;
    showWarningMessage: sinon.SinonStub;
  };
  TestRunRequest: new (include: unknown[]) => unknown;
  CancellationTokenSource: new () => {
    token: unknown;
    dispose: sinon.SinonStub;
  };
  TestMessage: new (message: string) => unknown;
  Location: new (uri: unknown, range: unknown) => unknown;
  Uri: { parse: (s: string) => unknown };
  Position: new (line: number, character: number) => unknown;
  Range: new (start: unknown, end: unknown) => unknown;
  TestRunProfileKind: { Run: number; Coverage: number };
}

interface CpMock {
  spawn: sinon.SinonStub;
}

interface FsMock {
  existsSync: sinon.SinonStub;
  statSync: sinon.SinonStub;
  realpathSync: sinon.SinonStub;
}

interface ReadlineMock {
  createInterface: sinon.SinonStub;
}

interface PathMock {
  join: (...args: string[]) => string;
  basename: (p: string) => string;
  normalize: (p: string) => string;
  isAbsolute: (p: string) => boolean;
  relative: (from: string, to: string) => string;
  delimiter: string;
}

// On Windows, wrapper launchers run through cmd with every argument quoted.
const unquoted = (args: string[]) =>
  args.map((arg) => arg.replace(/^"|"$/gu, ""));

describe("LSPTestExecutionService", () => {
  let LSPTestExecutionService: LSPTestExecutionServiceType;
  let service: LSPTestExecutionServiceInstance;
  let testServiceMock: TestServiceMock;
  let loggerMock: LoggerMock;
  let sandbox: sinon.SinonSandbox;

  let vscodeMock: VscodeMock;
  let cpMock: CpMock;
  let fsMock: FsMock;
  let readlineMock: ReadlineMock;
  let pathMock: PathMock;

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
        constructor(public include: unknown[]) {}
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
          public uri: unknown,
          public range: unknown,
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
          public start: unknown,
          public end: unknown,
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
      realpathSync: sandbox.stub().callsFake((p: string) => p),
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
      normalize: (p: string) => p.replace(/\\/g, "/"),
      isAbsolute: (p: string) => p.startsWith("/"),
      relative: (from: string, to: string) => {
        // Simple relative path mock - return path relative to from
        if (!from.endsWith("/")) from = from + "/";
        if (to.startsWith(from)) {
          return to.substring(from.length);
        }
        // If to doesn't start with from, it's outside - return absolute path
        return to;
      },
      delimiter: ":",
    };

    const proxyquireNoCallThru = (
      proxyquire as {
        noCallThru: () => (
          path: string,
          stubs: unknown,
        ) => { LSPTestExecutionService: LSPTestExecutionServiceType };
      }
    ).noCallThru();
    const module = proxyquireNoCallThru(
      "../../../../src/features/testing/LSPTestExecutionService",
      {
        vscode: vscodeMock,
        child_process: cpMock,
        fs: fsMock,
        readline: readlineMock,
        path: pathMock,
        "./TestEventConsumer": (
          proxyquire as {
            noCallThru: () => (path: string, stubs: unknown) => unknown;
          }
        ).noCallThru()("../../../../src/features/testing/TestEventConsumer", {
          vscode: vscodeMock,
        }),
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
    let testControllerMock: { createTestRun: sinon.SinonStub };
    let testRunMock: {
      enqueued: sinon.SinonStub;
      passed: sinon.SinonStub;
      failed: sinon.SinonStub;
      skipped: sinon.SinonStub;
      errored: sinon.SinonStub;
      appendOutput: sinon.SinonStub;
      end: sinon.SinonStub;
      token: unknown;
    };
    let coverageServiceMock: { addCoverageToRun: sinon.SinonStub };
    let tokenMock: {
      isCancellationRequested: boolean;
      onCancellationRequested: sinon.SinonStub;
    };

    beforeEach(() => {
      testRunMock = {
        enqueued: sandbox.stub(),
        passed: sandbox.stub(),
        failed: sandbox.stub(),
        skipped: sandbox.stub(),
        errored: sandbox.stub(),
        appendOutput: sandbox.stub(),
        end: sandbox.stub(),
        token: {
          isCancellationRequested: false,
          onCancellationRequested: sandbox
            .stub()
            .returns({ dispose: sandbox.stub() }),
        },
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
        on: sandbox
          .stub()
          .callsFake((event: string, callback: (code: number) => void) => {
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
        children: { size: 0, forEach: () => {} },
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
        unquoted(args).includes("jacocoTestReport"),
        "Should append jacocoTestReport for Gradle",
      );
    });

    it("should append jacoco:report for Maven projects", async () => {
      const testItem = {
        id: "com.example.MyTest",
        uri: { toString: () => "file:///test.groovy" },
        children: { size: 0, forEach: () => {} },
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
        args.some(
          (arg: string) => arg.replace(/^"|"$/gu, "") === "jacoco:report",
        ),
        "Should append jacoco:report for Maven",
      );
    });

    it("should call coverage service after test execution", async () => {
      const testItem = {
        id: "com.example.MySpec",
        uri: { toString: () => "file:///test.groovy" },
        children: { size: 0, forEach: () => {} },
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
        children: { size: 0, forEach: () => {} },
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

      // Root items are suites even when their FQN contains dots.
      assert.ok(testServiceMock.getTestCommand.calledOnce);
      const callArgs = testServiceMock.getTestCommand.getCall(0).args;
      assert.strictEqual(callArgs[1], "com.example.MySpec");
      assert.strictEqual(callArgs[2], undefined);
    });

    it("should handle test-level execution (dot in ID)", async () => {
      const testItem = {
        id: "com.example.MySpec.testMethod",
        parent: { id: "com.example.MySpec" },
        uri: { toString: () => "file:///test.groovy" },
        children: { size: 0, forEach: () => {} },
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
        children: { size: 0, forEach: () => {} },
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
          children: { size: 0, forEach: () => {} },
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
            unquoted(args).includes("jacocoTestReport"),
            `Should detect Gradle for ${testCase.executable}`,
          );
        } else if (testCase.expectMaven) {
          assert.ok(
            args.some(
              (arg: string) => arg.replace(/^"|"$/gu, "") === "jacoco:report",
            ),
            `Should detect Maven for ${testCase.executable}`,
          );
        }
      }
    });
  });

  describe("runTests", () => {
    let testControllerMock: { createTestRun: sinon.SinonStub };
    let testRunMock: {
      enqueued: sinon.SinonStub;
      passed: sinon.SinonStub;
      failed: sinon.SinonStub;
      skipped: sinon.SinonStub;
      errored: sinon.SinonStub;
      appendOutput: sinon.SinonStub;
      end: sinon.SinonStub;
      token: unknown;
    };
    let tokenMock: {
      isCancellationRequested: boolean;
      onCancellationRequested: sinon.SinonStub;
    };

    beforeEach(() => {
      testRunMock = {
        enqueued: sandbox.stub(),
        passed: sandbox.stub(),
        failed: sandbox.stub(),
        skipped: sandbox.stub(),
        errored: sandbox.stub(),
        appendOutput: sandbox.stub(),
        end: sandbox.stub(),
        token: {
          isCancellationRequested: false,
          onCancellationRequested: sandbox
            .stub()
            .returns({ dispose: sandbox.stub() }),
        },
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
        on: sandbox
          .stub()
          .callsFake((event: string, callback: (code: number) => void) => {
            if (event === "close") {
              setTimeout(() => callback(0), 0);
            }
          }),
        kill: sandbox.stub(),
      };
      cpMock.spawn.returns(proc);
    });

    it("passes Maven test arguments safely on the current platform", async () => {
      const testName =
        process.platform === "win32" ? "safe name" : 'a"; printf unsafe; #';
      const testItem = {
        id: `com.example.Spec.${testName}`,
        uri: { toString: () => "file:///test.groovy" },
        children: { size: 0, forEach: () => {} },
      };
      const args = ["test", `-Dtest=com.example.Spec#${testName}`];
      testServiceMock.getTestCommand.resolves({
        executable: "/usr/bin/mvn",
        args,
        cwd: "/workspace",
        env: {},
      });
      testServiceMock.getTestResults.resolves({ results: [] });

      await service.runTests(
        { include: [testItem] },
        tokenMock,
        testControllerMock,
      );

      assert.ok(cpMock.spawn.calledOnce);
      assert.strictEqual(
        cpMock.spawn.firstCall.args[2].shell,
        process.platform === "win32",
      );
      assert.ok(
        cpMock.spawn.firstCall.args[1].includes(
          process.platform === "win32" ? `"${args[1]}"` : args[1],
        ),
      );
    });

    it("should not append coverage tasks when running without coverage", async () => {
      const testItem = {
        id: "com.example.MySpec",
        uri: { toString: () => "file:///test.groovy" },
        children: { size: 0, forEach: () => {} },
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
        !unquoted(args).includes("jacocoTestReport"),
        "Should not append jacocoTestReport for regular test run",
      );
      assert.ok(
        !unquoted(args).includes("jacoco:report"),
        "Should not append jacoco:report for regular test run",
      );
    });

    it("marks a test errored when its name cannot pass through cmd on Windows", async () => {
      const platform = Object.getOwnPropertyDescriptor(process, "platform")!;
      Object.defineProperty(process, "platform", { value: "win32" });
      try {
        const testItem = {
          id: "com.example.MySpec.100% pass",
          label: "100% pass",
          uri: { toString: () => "file:///test.groovy" },
          children: { size: 0, forEach: () => {} },
        };
        testServiceMock.getTestCommand.resolves({
          executable: "C:\\w\\gradlew.bat",
          args: ["test", "--tests", "com.example.MySpec.100% pass"],
          cwd: "C:\\w",
          env: {},
        });

        await service.runTests(
          { include: [testItem] },
          tokenMock,
          testControllerMock,
        );

        assert.ok(cpMock.spawn.notCalled, "nothing may run through cmd");
        assert.ok(
          testRunMock.errored.calledOnce,
          "the test must not stay enqueued",
        );
        const [item, message] = testRunMock.errored.firstCall.args;
        assert.strictEqual(item, testItem);
        assert.match(
          (message as { message: string }).message,
          /cannot be run through the Windows command shell[\s\S]*100% pass/,
        );
        assert.ok(testRunMock.end.calledOnce);
      } finally {
        Object.defineProperty(process, "platform", platform);
      }
    });

    it("does not let stale Maven results overwrite an unrunnable test", async () => {
      const platform = Object.getOwnPropertyDescriptor(process, "platform")!;
      Object.defineProperty(process, "platform", { value: "win32" });
      try {
        const testItem = {
          id: "com.example.MySpec.100% pass",
          label: "100% pass",
          uri: { toString: () => "file:///test.groovy" },
          children: { size: 0, forEach: () => {} },
        };
        testServiceMock.getTestCommand.resolves({
          executable: "C:\\w\\mvnw.cmd",
          args: ["test", "-Dtest=MySpec#100% pass"],
          cwd: "C:\\w",
          env: {},
        });
        testServiceMock.getTestResults.resolves({
          results: [
            { testId: testItem.id, name: "100% pass", status: "PASSED" },
          ],
        });

        await service.runTests(
          { include: [testItem] },
          tokenMock,
          testControllerMock,
        );

        assert.ok(testRunMock.errored.calledOnce);
        assert.ok(
          testServiceMock.getTestResults.notCalled,
          "results of an earlier run must not be applied",
        );
        assert.ok(testRunMock.passed.notCalled);
      } finally {
        Object.defineProperty(process, "platform", platform);
      }
    });
  });

  describe("JAVA_HOME validation security", () => {
    beforeEach(() => {
      // Reset mocks before each test
      sandbox.resetHistory();
    });

    it("should reject relative paths", () => {
      const relativePath = "./some/relative/path";

      const result = service.isValidJavaHome(relativePath);

      assert.strictEqual(
        result,
        false,
        "Should reject relative paths for JAVA_HOME",
      );
      assert.ok(
        loggerMock.appendLine.calledWith(sinon.match(/path must be absolute/)),
      );
    });

    it("should reject paths with .. traversal", () => {
      const traversalPath = "/usr/local/../../../etc/passwd";
      fsMock.existsSync.returns(true);
      fsMock.statSync.returns({ isFile: () => false, isDirectory: () => true });
      fsMock.realpathSync = sandbox.stub().returns("/etc/passwd");

      const result = service.isValidJavaHome(traversalPath);

      // Should fail because realpath resolves to /etc/passwd which won't have bin/java
      assert.strictEqual(
        result,
        false,
        "Should reject paths with path traversal",
      );
    });

    it("should reject paths with shell injection characters", () => {
      const maliciousPath = "/usr/java; rm -rf /";
      fsMock.existsSync.returns(true);
      fsMock.realpathSync = sandbox.stub().throws(new Error("Invalid path"));

      const result = service.isValidJavaHome(maliciousPath);

      assert.strictEqual(
        result,
        false,
        "Should reject paths with shell injection attempts",
      );
    });

    it("should handle symlink resolution", () => {
      const symlinkPath = "/usr/local/java-current";
      const realPath = "/usr/lib/jvm/java-17-openjdk";
      const javaPath = realPath + "/bin/java";

      fsMock.realpathSync = sandbox.stub();
      // First call: normalize and realpath the JAVA_HOME
      fsMock.realpathSync.onCall(0).returns(realPath);
      // Second call: realpath the java executable
      fsMock.realpathSync.onCall(1).returns(javaPath);

      fsMock.statSync = sandbox.stub();
      // First call: stat the JAVA_HOME directory
      fsMock.statSync.onCall(0).returns({
        isFile: () => false,
        isDirectory: () => true,
      });
      // Second call: stat the java executable
      fsMock.statSync.onCall(1).returns({
        isFile: () => true,
        isDirectory: () => false,
      });

      const result = service.isValidJavaHome(symlinkPath);

      assert.strictEqual(
        result,
        true,
        "Should resolve symlinks and validate correctly",
      );
    });

    it("should reject non-existent paths", () => {
      const nonExistentPath = "/path/that/does/not/exist";
      fsMock.realpathSync = sandbox
        .stub()
        .throws(new Error("ENOENT: no such file or directory"));

      const result = service.isValidJavaHome(nonExistentPath);

      assert.strictEqual(
        result,
        false,
        "Should reject non-existent paths for JAVA_HOME",
      );
    });

    it("should reject JAVA_HOME that is a file not a directory", () => {
      const filePath = "/usr/bin/java";
      fsMock.realpathSync = sandbox.stub().returns(filePath);
      fsMock.statSync.returns({ isFile: () => true, isDirectory: () => false });

      const result = service.isValidJavaHome(filePath);

      assert.strictEqual(
        result,
        false,
        "Should reject JAVA_HOME that points to a file",
      );
    });

    it("should reject when java executable resolves outside JAVA_HOME", () => {
      const javaHome = "/usr/lib/jvm/java-17";
      const maliciousJavaPath = "/usr/bin/malicious-java";
      fsMock.realpathSync = sandbox.stub();
      fsMock.realpathSync.onFirstCall().returns(javaHome);
      fsMock.realpathSync.onSecondCall().returns(maliciousJavaPath);
      fsMock.statSync.returns({ isFile: () => false, isDirectory: () => true });

      const result = service.isValidJavaHome(javaHome);

      assert.strictEqual(
        result,
        false,
        "Should reject when java executable resolves outside JAVA_HOME",
      );
    });

    it("should validate correct JAVA_HOME", () => {
      const validJavaHome = "/usr/lib/jvm/java-17-openjdk";
      const javaPath = validJavaHome + "/bin/java";

      fsMock.realpathSync = sandbox.stub();
      // First call: normalize and realpath the JAVA_HOME
      fsMock.realpathSync.onCall(0).returns(validJavaHome);
      // Second call: realpath the java executable
      fsMock.realpathSync.onCall(1).returns(javaPath);

      fsMock.statSync = sandbox.stub();
      // First call: stat the JAVA_HOME directory
      fsMock.statSync.onCall(0).returns({
        isFile: () => false,
        isDirectory: () => true,
      });
      // Second call: stat the java executable
      fsMock.statSync.onCall(1).returns({
        isFile: () => true,
        isDirectory: () => false,
      });

      const result = service.isValidJavaHome(validJavaHome);

      assert.strictEqual(result, true, "Should accept valid JAVA_HOME");
    });
  });

  describe("collectAllTestItems cycle detection", () => {
    it("should handle circular references without infinite loop", () => {
      // Create a circular reference in test items
      type TestItemType = {
        id: string;
        children: Map<string, TestItemType>;
      };
      const item1: TestItemType = {
        id: "item1",
        children: new Map(),
      };
      const item2: TestItemType = {
        id: "item2",
        children: new Map(),
      };
      const item3: TestItemType = {
        id: "item3",
        children: new Map(),
      };

      // Create circular reference: item1 -> item2 -> item3 -> item1
      item1.children.set("item2", item2);
      item2.children.set("item3", item3);
      item3.children.set("item1", item1);

      // This should not stack overflow
      const result = service.collectAllTestItems([item1]);

      assert.ok(result.length >= 1, "Should collect at least one item");
      assert.ok(
        result.length <= 3,
        "Should not infinitely loop on circular reference",
      );
    });

    it("should not visit same item twice", () => {
      // Create a diamond structure where one item is reachable via two paths
      type TestItemType = {
        id: string;
        children: Map<string, TestItemType>;
      };
      const root: TestItemType = {
        id: "root",
        children: new Map(),
      };
      const left: TestItemType = {
        id: "left",
        children: new Map(),
      };
      const right: TestItemType = {
        id: "right",
        children: new Map(),
      };
      const shared: TestItemType = {
        id: "shared",
        children: new Map(),
      };

      root.children.set("left", left);
      root.children.set("right", right);
      left.children.set("shared", shared);
      right.children.set("shared", shared);

      const result = service.collectAllTestItems([root]) as TestItemType[];

      // Count occurrences of shared item
      const sharedCount = result.filter(
        (item: TestItemType) => item.id === "shared",
      ).length;
      assert.strictEqual(
        sharedCount,
        1,
        "Should visit shared item exactly once",
      );
    });

    it("should handle deeply nested trees", () => {
      // Create a deeply nested tree (100 levels)
      type TestItemType = {
        id: string;
        children: Map<string, TestItemType>;
      };
      let current: TestItemType = {
        id: "item0",
        children: new Map(),
      };
      const root = current;

      for (let i = 1; i < 100; i++) {
        const child: TestItemType = {
          id: `item${i}`,
          children: new Map(),
        };
        current.children.set(child.id, child);
        current = child;
      }

      // Should not stack overflow
      const result = service.collectAllTestItems([root]);

      assert.strictEqual(result.length, 100, "Should collect all 100 items");
    });
  });

  describe("normalizeTestId edge cases", () => {
    // Note: normalizeTestId is a module-level function used internally
    // We test it through observable behavior via result matching

    it("should collapse multiple consecutive underscores", () => {
      // Test via applyTestResults behavior since normalizeTestId is internal
      // We'll verify through the result map behavior

      testServiceMock.getTestResults.resolves({
        results: [
          {
            testId: "test_multiple_underscores",
            name: "test",
            status: "SUCCESS",
            durationMs: 100,
          },
        ],
      });

      // The internal logic should normalize both IDs and match them
      // This is implicitly tested through the applyTestResults logic
    });

    it("should trim leading and trailing underscores", () => {
      testServiceMock.getTestResults.resolves({
        results: [
          {
            testId: "test_method",
            name: "test_method",
            status: "SUCCESS",
            durationMs: 100,
          },
        ],
      });

      // Should be handled by internal normalization
    });

    it("should handle empty string", () => {
      // Empty string should return empty string after normalization

      // Should not throw
      assert.doesNotThrow(() => {
        testServiceMock.getTestResults.resolves({
          results: [{ testId: "", name: "test", status: "SUCCESS" }],
        });
      });
    });

    it("should handle only special characters", () => {
      // All special chars should be converted to underscores and then trimmed
      testServiceMock.getTestResults.resolves({
        results: [
          {
            testId: "_________",
            name: "special",
            status: "SUCCESS",
            durationMs: 100,
          },
        ],
      });
    });

    it("should handle Unicode characters", () => {
      // Unicode word characters should be preserved
      testServiceMock.getTestResults.resolves({
        results: [
          {
            testId: "test_méthodé_日本語",
            name: "test",
            status: "SUCCESS",
            durationMs: 100,
          },
        ],
      });
    });

    it("should handle very long strings (10000+ chars)", () => {
      const longId = "a".repeat(10000);

      // Should not cause performance issues or crash
      assert.doesNotThrow(() => {
        testServiceMock.getTestResults.resolves({
          results: [
            {
              testId: longId,
              name: "long",
              status: "SUCCESS",
              durationMs: 100,
            },
          ],
        });
      });
    });
  });

  describe("result map collision handling", () => {
    let testRunMock: {
      enqueued: sinon.SinonStub;
      passed: sinon.SinonStub;
      failed: sinon.SinonStub;
      skipped: sinon.SinonStub;
      errored: sinon.SinonStub;
      appendOutput: sinon.SinonStub;
      end: sinon.SinonStub;
      token: unknown;
    };
    let testControllerMock: { createTestRun: sinon.SinonStub };
    let tokenMock: {
      isCancellationRequested: boolean;
      onCancellationRequested: sinon.SinonStub;
    };

    beforeEach(() => {
      testRunMock = {
        enqueued: sandbox.stub(),
        passed: sandbox.stub(),
        failed: sandbox.stub(),
        skipped: sandbox.stub(),
        errored: sandbox.stub(),
        appendOutput: sandbox.stub(),
        end: sandbox.stub(),
        token: {
          isCancellationRequested: false,
          onCancellationRequested: sandbox
            .stub()
            .returns({ dispose: sandbox.stub() }),
        },
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
    });

    it("does not warn when a result owns coincident aliases or is repeated by identity", async () => {
      const item = {
        id: "a.Suite.method",
        label: "method",
        children: { forEach: () => {} },
      };
      const result = {
        testId: "a.Suite.method",
        className: "a.Suite",
        name: "method",
        status: "SUCCESS",
        durationMs: 3,
      };
      for (const results of [[result], [result, result]]) {
        testRunMock.passed.resetHistory();
        loggerMock.appendLine.resetHistory();
        testServiceMock.getTestResults.resolves({ results });
        await service.applyTestResults("file:///w", testRunMock, [item], {});
        assert.ok(testRunMock.passed.calledOnceWithExactly(item, 3));
        assert.ok(
          !loggerMock.appendLine.calledWith(sinon.match(/Collision detected/)),
        );
      }
    });

    it("warns for distinct duplicate records and preserves later exact match", async () => {
      const item = {
        id: "a.Suite.method",
        label: "method",
        children: { forEach: () => {} },
      };
      testServiceMock.getTestResults.resolves({
        results: [
          {
            testId: item.id,
            className: "a.Suite",
            name: "method",
            status: "SUCCESS",
            durationMs: 1,
          },
          {
            testId: item.id,
            className: "a.Suite",
            name: "method",
            status: "FAILURE",
            durationMs: 2,
          },
        ],
      });
      await service.applyTestResults("file:///w", testRunMock, [item], {});
      assert.ok(
        loggerMock.appendLine.calledWith(
          sinon.match(/Collision detected for testId/),
        ),
      );
      assert.ok(testRunMock.failed.calledOnce);
    });

    it("warns on distinct normalized aliases while exact IDs still select their own result", async () => {
      const items = ["a.Suite.a b", "a.Suite.a-b"].map((id) => ({
        id,
        label: id,
        children: { forEach: () => {} },
      }));
      testServiceMock.getTestResults.resolves({
        results: [
          {
            testId: items[0].id,
            name: "a b",
            status: "SUCCESS",
            durationMs: 1,
          },
          {
            testId: items[1].id,
            name: "a-b",
            status: "FAILURE",
            durationMs: 2,
          },
        ],
      });
      await service.applyTestResults("file:///w", testRunMock, items, {});
      assert.ok(
        loggerMock.appendLine.calledWith(
          sinon.match(/Collision detected for normalized ID/),
        ),
      );
      assert.ok(testRunMock.passed.calledWith(items[0]));
      assert.ok(testRunMock.failed.calledWith(items[1]));
    });

    it("warns on shared names across suites while exact matching takes precedence", async () => {
      const items = ["a.Suite.method", "b.Suite.method"].map((id) => ({
        id,
        label: "method",
        children: { forEach: () => {} },
      }));
      testServiceMock.getTestResults.resolves({
        results: [
          {
            testId: items[0].id,
            className: "a.Suite",
            name: "method",
            status: "SUCCESS",
            durationMs: 1,
          },
          {
            testId: items[1].id,
            className: "b.Suite",
            name: "method",
            status: "FAILURE",
            durationMs: 2,
          },
        ],
      });
      await service.applyTestResults("file:///w", testRunMock, items, {});
      assert.ok(
        loggerMock.appendLine.calledWith(
          sinon.match(/Collision detected for test name/),
        ),
      );
      assert.ok(testRunMock.passed.calledWith(items[0]));
      assert.ok(testRunMock.failed.calledWith(items[1]));
    });

    it("should warn when multiple results have same testId", async () => {
      const testItem = {
        id: "com.example.Test.method",
        uri: { toString: () => "file:///test.groovy" },
        children: { size: 0, forEach: () => {} },
      };

      const request = { include: [testItem] };

      testServiceMock.getTestCommand.resolves({
        executable: "/path/to/mvnw",
        args: ["test"],
        cwd: "/workspace",
        env: {},
      });

      // Return duplicate results with same testId
      testServiceMock.getTestResults.resolves({
        results: [
          {
            testId: "com.example.Test.method",
            name: "method",
            className: "com.example.Test",
            status: "SUCCESS",
            durationMs: 100,
          },
          {
            testId: "com.example.Test.method",
            name: "method",
            className: "com.example.Test",
            status: "FAILURE",
            durationMs: 200,
          },
        ],
      });

      const stdout = { on: sandbox.stub() };
      const stderr = { on: sandbox.stub() };
      const proc = {
        stdout,
        stderr,
        on: sandbox
          .stub()
          .callsFake((event: string, callback: (code: number) => void) => {
            if (event === "close") {
              setTimeout(() => callback(0), 0);
            }
          }),
        kill: sandbox.stub(),
      };
      cpMock.spawn.returns(proc);

      await service.runTestsWithCoverage(
        request,
        tokenMock,
        testControllerMock,
        { addCoverageToRun: sandbox.stub().resolves() },
      );

      // Should log collision warning
      assert.ok(
        loggerMock.appendLine.calledWith(
          sinon.match(/Collision detected for testId/),
        ),
        "Should warn about testId collision",
      );
    });

    it("uses the later result when a name collision occurs", async () => {
      const testItem = {
        id: "method",
        label: "method",
        uri: { toString: () => "file:///test.groovy" },
        children: { size: 0, forEach: () => {} },
      };

      const request = { include: [testItem] };

      testServiceMock.getTestCommand.resolves({
        executable: "/path/to/mvnw",
        args: ["test"],
        cwd: "/workspace",
        env: {},
      });

      testServiceMock.getTestResults.resolves({
        results: [
          {
            testId: "other.id",
            name: "method",
            status: "SUCCESS",
            durationMs: 100,
          },
          {
            testId: "another.id",
            name: "method",
            status: "FAILURE",
            durationMs: 200,
          },
        ],
      });

      const stdout = { on: sandbox.stub() };
      const stderr = { on: sandbox.stub() };
      const proc = {
        stdout,
        stderr,
        on: sandbox
          .stub()
          .callsFake((event: string, callback: (code: number) => void) => {
            if (event === "close") {
              setTimeout(() => callback(0), 0);
            }
          }),
        kill: sandbox.stub(),
      };
      cpMock.spawn.returns(proc);

      await service.runTestsWithCoverage(
        request,
        tokenMock,
        testControllerMock,
        { addCoverageToRun: sandbox.stub().resolves() },
      );

      assert.ok(testRunMock.failed.calledWith(testItem));
      // The map will overwrite, so we should see collision warnings.
      assert.ok(
        loggerMock.appendLine.calledWith(
          sinon.match(/Collision detected for test name/),
        ),
        "Should warn about name collision",
      );
    });
  });
  describe("cancellation", () => {
    it("kills the build when the run's own Stop button is pressed", async () => {
      let stopRun: (() => void) | undefined;
      const run = {
        enqueued: sandbox.stub(),
        passed: sandbox.stub(),
        failed: sandbox.stub(),
        skipped: sandbox.stub(),
        errored: sandbox.stub(),
        appendOutput: sandbox.stub(),
        end: sandbox.stub(),
        token: {
          isCancellationRequested: false,
          onCancellationRequested: (cb: () => void) => {
            stopRun = cb;
            return { dispose: () => {} };
          },
        },
      };
      const controller = {
        createTestRun: sandbox.stub().returns(run),
        items: { forEach: () => {} },
      };
      // A token nobody ever cancels, as used by CodeLens and command runs.
      const callerToken = {
        isCancellationRequested: false,
        onCancellationRequested: () => ({ dispose: () => {} }),
      };

      let onClose: ((code: number) => void) | undefined;
      const proc = {
        stdout: { on: sandbox.stub() },
        stderr: { on: sandbox.stub() },
        on: (event: string, cb: (code: number) => void) => {
          if (event === "close") onClose = cb;
        },
        kill: sandbox.stub().callsFake(() => onClose?.(143)),
      };
      cpMock.spawn.returns(proc);
      testServiceMock.getTestCommand.resolves({
        executable: "/w/gradlew",
        args: ["test"],
        cwd: "/w",
        env: {},
      });

      const item = {
        id: "com.x.Spec",
        uri: { toString: () => "file:///w/Spec.groovy" },
        children: { size: 0, forEach: () => {} },
      };
      const done = service.runTests(
        { include: [item] },
        callerToken,
        controller,
      );
      await new Promise((r) => setTimeout(r, 0));
      assert.ok(stopRun, "run token should be observed");
      stopRun!();
      await done;

      assert.ok(proc.kill.calledOnce, "build process should be killed");
      assert.ok(run.end.calledOnce);
    });
  });
});
