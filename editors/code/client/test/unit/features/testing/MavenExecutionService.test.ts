import * as assert from "assert";
import * as sinon from "sinon";
import proxyquire from "proxyquire";

describe("MavenExecutionService", () => {
  let MavenExecutionService: any;
  let service: any;
  let loggerMock: any;
  let sandbox: sinon.SinonSandbox;

  let vscodeMock: any;
  let readlineMock: any;

  beforeEach(() => {
    sandbox = sinon.createSandbox();

    // Mock logger
    loggerMock = {
      appendLine: sandbox.stub(),
    };

    // Load MavenExecutionService with mocks
    // Need to mock vscode globally since it's used by TestEventConsumer too
    vscodeMock = {
      workspace: {
        workspaceFolders: [{ uri: { fsPath: "/workspace" } }],
      },
      window: {
        showErrorMessage: sandbox.stub().resolves(),
        showWarningMessage: sandbox.stub(),
      },
      TestRunRequest: class {
        constructor(public include: any[]) {}
      },
      CancellationTokenSource: class {
        token = {};
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
        parse: (s: string) => ({ toString: () => s }),
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
      TestRunProfileKind: { Run: 1 },
    };

    // Mock child_process
    const cpMock = {
      spawn: sandbox.stub(),
    };

    // Mock fs
    const fsMock = {
      existsSync: sandbox.stub().returns(true), // Assume pom.xml exists
      statSync: sandbox.stub().returns({ isFile: () => false }),
    };

    // Mock readline
    readlineMock = {
      createInterface: sandbox.stub().returns({
        on: sandbox.stub(),
        close: sandbox.stub(),
      }),
    };

    const module = (proxyquire as any).noCallThru()(
      "../../../../src/features/testing/MavenExecutionService",
      {
        vscode: vscodeMock,
        child_process: cpMock,
        fs: fsMock,
        readline: readlineMock,
        "./TestEventConsumer": (proxyquire as any).noCallThru()(
          "../../../../src/features/testing/TestEventConsumer",
          { vscode: vscodeMock },
        ),
      },
    );
    MavenExecutionService = module.MavenExecutionService;
    service = new MavenExecutionService(loggerMock);
    // Expose mocks for tests
    (service as any).cpMock = cpMock;
    (service as any).fsMock = fsMock;
  });

  afterEach(() => {
    sandbox.restore();
  });

  describe("buildTestFilter", () => {
    it("should quote test names with spaces to prevent shell word splitting", () => {
      const mockItem = {
        id: "com.example.MySpec.multi-argument capture",
        children: { size: 0 },
      };
      const request = { include: [mockItem] };
      const filter = (service as any).buildTestFilter(request);
      assert.ok(filter[0].includes('\"'));
    });
  });

  describe("spawnMaven", () => {
    it("should detect HTTP blocker error in stderr and notify user", async () => {
      // Mock spawn process
      const stdout = { on: sandbox.stub() };
      const stderr = { on: sandbox.stub() };
      const proc = {
        stdout,
        stderr,
        on: sandbox.stub(),
        kill: sandbox.stub(),
      };
      (service as any).cpMock.spawn.returns(proc);

      const consumerMock = {
        processLine: sandbox.stub(),
        getAllRegisteredItems: sandbox.stub().returns([]),
        markPassed: sandbox.stub(),
        markFailed: sandbox.stub(),
      };

      const token = {
        onCancellationRequested: sandbox
          .stub()
          .returns({ dispose: sandbox.stub() }),
      };

      const promise = (service as any).spawnMaven(
        "/cwd",
        [],
        consumerMock,
        token,
      );

      // Simulate stderr with error
      const errorOutput =
        "Could not transfer artifact ... maven-default-http-blocker ...";

      // Trigger stderr data
      const stderrCallback = stderr.on.getCall(0).args[1];
      stderrCallback(errorOutput);

      // Trigger close with error code 1
      const closeCallback = proc.on.getCall(0).args[1];
      closeCallback(1);

      await promise;

      assert.ok(
        vscodeMock.window.showErrorMessage.calledWithMatch(
          "Maven Blocked HTTP Repository",
        ),
        "Should notify user about Maven blocking HTTP repositories via stderr",
      );
    });

    it("should detect HTTP blocker error in stdout and notify user", async () => {
      // Mock spawn process
      const stdout = { on: sandbox.stub() };
      const stderr = { on: sandbox.stub() };
      const proc = {
        stdout,
        stderr,
        on: sandbox.stub(),
        kill: sandbox.stub(),
      };
      (service as any).cpMock.spawn.returns(proc);

      const consumerMock = {
        processLine: sandbox.stub(),
        getAllRegisteredItems: sandbox.stub().returns([]),
        markPassed: sandbox.stub(),
        markFailed: sandbox.stub(),
      };

      const token = {
        onCancellationRequested: sandbox
          .stub()
          .returns({ dispose: sandbox.stub() }),
      };

      // Setup readline mock to capture listener
      let lineListener: any;
      readlineMock.createInterface.returns({
        on: (event: string, listener: any) => {
          if (event === "line") lineListener = listener;
        },
        close: sandbox.stub(),
      });

      const promise = (service as any).spawnMaven(
        "/cwd",
        [],
        consumerMock,
        token,
      );

      // Simulate stdout with error
      const errorLine = "[ERROR] ... maven-default-http-blocker ...";

      // Wait for readline listener to be registered (it happens synchronously in spawnMaven)
      if (lineListener) {
        lineListener(errorLine);
      } else {
        assert.fail("Readline listener was not registered");
      }

      // Trigger close with error code 1
      const closeCallback = proc.on.getCall(0).args[1];
      closeCallback(1);

      await promise;

      assert.ok(
        vscodeMock.window.showErrorMessage.calledWithMatch(
          "Maven Blocked HTTP Repository",
        ),
        "Should notify user about Maven blocking HTTP repositories via stdout",
      );
    });
  });
});
