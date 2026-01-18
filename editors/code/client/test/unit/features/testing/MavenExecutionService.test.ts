import * as assert from "assert";
import * as sinon from "sinon";
import proxyquire from "proxyquire";

interface LoggerMock {
  appendLine: sinon.SinonStub;
}

interface VscodeMock {
  workspace: {
    workspaceFolders: { uri: { fsPath: string } }[];
  };
  window: {
    showErrorMessage: sinon.SinonStub;
    showWarningMessage: sinon.SinonStub;
  };
  TestRunRequest: new (include: unknown[]) => { include: unknown[] };
  CancellationTokenSource: new () => { token: unknown; dispose: sinon.SinonStub };
  TestMessage: new (message: string) => { message: string };
  Location: new (uri: unknown, range: unknown) => { uri: unknown; range: unknown };
  Uri: {
    parse: (s: string) => { toString: () => string };
  };
  Position: new (line: number, character: number) => { line: number; character: number };
  Range: new (start: unknown, end: unknown) => { start: unknown; end: unknown };
  TestRunProfileKind: { Run: number };
}

interface ReadlineMock {
  createInterface: sinon.SinonStub;
}

interface MavenExecutionServiceClass {
  new (logger: LoggerMock): {
    buildTestFilter: (request: unknown) => string[];
    cpMock?: unknown;
    fsMock?: unknown;
  };
}

describe("MavenExecutionService", () => {
  let MavenExecutionService: MavenExecutionServiceClass;
  let service: { buildTestFilter: (request: unknown) => string[]; cpMock?: unknown; fsMock?: unknown };
  let loggerMock: LoggerMock;
  let sandbox: sinon.SinonSandbox;

  let vscodeMock: VscodeMock;
  let readlineMock: ReadlineMock;

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
        constructor(public include: unknown[]) {}
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
          public uri: unknown,
          public range: unknown,
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
          public start: unknown,
          public end: unknown,
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

    const module = (proxyquire as { noCallThru: () => (path: string, stubs: unknown) => { MavenExecutionService: MavenExecutionServiceClass } }).noCallThru()(
      "../../../../src/features/testing/MavenExecutionService",
      {
        vscode: vscodeMock,
        child_process: cpMock,
        fs: fsMock,
        readline: readlineMock,
        "./TestEventConsumer": (proxyquire as { noCallThru: () => (path: string, stubs: unknown) => unknown }).noCallThru()(
          "../../../../src/features/testing/TestEventConsumer",
          { vscode: vscodeMock },
        ),
      },
    );
    MavenExecutionService = module.MavenExecutionService;
    service = new MavenExecutionService(loggerMock);
    // Expose mocks for tests
    (service as unknown).cpMock = cpMock;
    (service as unknown).fsMock = fsMock;
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
      const filter = (service as unknown).buildTestFilter(request);
      assert.ok(filter[0].includes('"'));
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
      (service as unknown).cpMock.spawn.returns(proc);

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

      const promise = (service as unknown).spawnMaven(
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
      (service as unknown).cpMock.spawn.returns(proc);

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
      let lineListener: ((line: string) => void) | undefined;
      readlineMock.createInterface.returns({
        on: (event: string, listener: unknown) => {
          if (event === "line") lineListener = listener;
        },
        close: sandbox.stub(),
      });

      const promise = (service as unknown).spawnMaven(
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
