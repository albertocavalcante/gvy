import * as assert from "assert";
import * as sinon from "sinon";
import proxyquire from "proxyquire";

type Deferred = {
  promise: Promise<void>;
  resolve: () => void;
  reject: (e: Error) => void;
};
function deferred(): Deferred {
  let resolve!: () => void;
  let reject!: (e: Error) => void;
  const promise = new Promise<void>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const State = { Stopped: 1, Starting: 3, Running: 2 };
const CloseAction = { DoNotRestart: 1, Restart: 2 };

interface FakeErrorHandler {
  closed: () => unknown;
}

interface FakeClientOptions {
  outputChannel?: unknown;
  errorHandler?: FakeErrorHandler;
}

class FakeClient {
  static instances: FakeClient[] = [];
  static nextStart: (() => Promise<void>) | undefined;
  state = State.Stopped;
  initializeResult: unknown = { serverInfo: { name: "gls", version: "9.9.9" } };
  static spawnLate = false;
  serverProcess: { kill: sinon.SinonStub } | undefined = FakeClient.spawnLate
    ? undefined
    : { kill: sinon.stub() };
  static closedResult: () => unknown = () => ({ action: CloseAction.Restart });
  createDefaultErrorHandler = () => ({
    error: () => ({ action: 1 }),
    closed: () => FakeClient.closedResult(),
  });
  stop = sinon.stub().callsFake(async () => {
    if (this.state !== State.Running) {
      throw new Error(
        `Client is not running and can't be stopped. It's current state is: ${this.state}`,
      );
    }
    this.state = State.Stopped;
  });
  constructor(
    public id: string,
    public name: string,
    public serverOptions: unknown,
    public clientOptions: FakeClientOptions,
  ) {
    FakeClient.instances.push(this);
  }
  isRunning() {
    return this.state === State.Running;
  }
  async start() {
    this.state = State.Starting;
    const gate = FakeClient.nextStart;
    FakeClient.nextStart = undefined;
    try {
      await (gate ? gate() : Promise.resolve());
    } catch (e) {
      this.state = State.Stopped;
      throw e;
    }
    this.state = State.Running;
  }
}

describe("server/client lifecycle", () => {
  let mod: {
    initializeClient: (ctx: unknown) => void;
    startClient: (channel?: unknown) => Promise<void>;
    stopClient: () => Promise<void>;
    restartClient: () => Promise<void>;
    getClient: () => FakeClient | undefined;
    onClientStarted: (cb: () => void) => { dispose: () => void };
  };
  let watchers: Array<{ dispose: sinon.SinonStub }>;
  let validateJava: sinon.SinonStub;
  let showJavaError: sinon.SinonStub;
  let setServerVersion: sinon.SinonStub;
  let createOutputChannel: sinon.SinonStub;
  let startTimeoutSeconds: number;
  let serverVmArgs: unknown;

  beforeEach(() => {
    FakeClient.instances = [];
    FakeClient.nextStart = undefined;
    FakeClient.spawnLate = false;
    startTimeoutSeconds = 120;
    serverVmArgs = undefined;
    FakeClient.closedResult = () => ({ action: CloseAction.Restart });
    watchers = [];
    validateJava = sinon.stub().resolves({
      isValid: true,
      resolution: { path: "/jdk", version: 21, source: "setting" },
    });
    showJavaError = sinon.stub().resolves();
    setServerVersion = sinon.stub();
    createOutputChannel = sinon
      .stub()
      .callsFake(() => ({ dispose: sinon.stub() }));

    const vscodeMock = {
      workspace: {
        workspaceFolders: [{ uri: { fsPath: "/w" } }],
        getConfiguration: () => ({ get: (_k: string, d?: unknown) => d }),
        createFileSystemWatcher: () => {
          const w = { dispose: sinon.stub() };
          watchers.push(w);
          return w;
        },
      },
      window: {
        showErrorMessage: sinon.stub().resolves(undefined),
        showInformationMessage: sinon.stub().resolves(undefined),
        createOutputChannel,
      },
      commands: { executeCommand: sinon.stub() },
      Uri: { parse: (s: string) => s },
    };

    mod = proxyquire.noCallThru()("../../../src/server/client", {
      vscode: vscodeMock,
      "vscode-languageclient/node": {
        LanguageClient: FakeClient,
        State,
        CloseAction,
      },
      "../java/validator": {
        validateJava,
        showJavaError,
        getJavaExecutable: () => "/jdk/bin/java",
      },
      "../ui/statusBar": {
        setClient: sinon.stub(),
        getStatusBarManager: () => ({
          setOutputChannel: sinon.stub(),
          setServerVersion,
        }),
      },
      "../configuration/settings": {
        getConfiguration: () => ({
          serverPath: undefined,
          startTimeoutSeconds,
          serverVmArgs,
        }),
      },
      "../services/ServerResolver": {
        ServerResolver: class {
          async resolve() {
            return "/ext/server/gls.jar";
          }
        },
      },
    });
    mod.initializeClient({
      subscriptions: [],
      asAbsolutePath: (p: string) => p,
    });
  });

  it("serializes restart behind an in-flight start (no second server)", async () => {
    const firstStart = deferred();
    FakeClient.nextStart = () => firstStart.promise;
    const starting = mod.startClient();
    const restarting = mod.restartClient();

    await new Promise((r) => setTimeout(r, 0));
    assert.strictEqual(
      FakeClient.instances.length,
      1,
      "restart must wait for the start",
    );

    firstStart.resolve();
    await starting;
    await restarting;

    const [first, second] = FakeClient.instances;
    assert.ok(first.stop.calledOnce, "first server must be stopped");
    assert.strictEqual(first.state, State.Stopped);
    assert.strictEqual(second.state, State.Running);
    assert.strictEqual(mod.getClient(), second);
  });

  it("keeps the server output channel across restarts", async () => {
    const channel = { name: "Groovy Language Server" };
    await mod.startClient(channel);
    await mod.restartClient();
    assert.strictEqual(FakeClient.instances.length, 2);
    assert.strictEqual(
      FakeClient.instances[1].clientOptions.outputChannel,
      channel,
    );
  });

  it("disposes the per-start file watcher when the server stops", async () => {
    await mod.startClient();
    await mod.stopClient();
    assert.strictEqual(watchers.length, 1);
    assert.ok(watchers[0].dispose.calledOnce);
  });

  it("does not create a new trace channel on every restart", async () => {
    await mod.startClient();
    await mod.restartClient();
    await mod.restartClient();
    assert.ok(createOutputChannel.callCount <= 1);
  });

  it("does not wait for the user to dismiss the Java error", async () => {
    validateJava.resolves({
      isValid: false,
      error: "No Java installation found",
    });
    showJavaError.returns(new Promise(() => {})); // notification left open
    await assert.rejects(mod.startClient(), /Java validation failed/);
    assert.ok(showJavaError.calledOnce);
  });

  it("does not keep a client whose start failed", async () => {
    FakeClient.nextStart = () => Promise.reject(new Error("spawn java ENOENT"));
    await assert.rejects(mod.startClient(), /ENOENT/);
    assert.strictEqual(mod.getClient(), undefined);
    await mod.startClient();
    assert.strictEqual(mod.getClient()?.state, State.Running);
  });

  it("uses the groovy section so groovy.trace.server reaches the client", async () => {
    await mod.startClient();
    assert.strictEqual(FakeClient.instances[0].id, "groovy");
  });

  it("notifies listeners after every successful start", async () => {
    const started = sinon.stub();
    mod.onClientStarted(started);
    await mod.startClient();
    await mod.restartClient();
    assert.strictEqual(started.callCount, 2);
  });

  it("stops a client whose start failed after spawning, killing its process", async () => {
    FakeClient.nextStart = () => Promise.reject(new Error("initialize failed"));
    await assert.rejects(mod.startClient(), /initialize failed/);
    const [failed] = FakeClient.instances;
    assert.ok(failed.stop.calledOnce, "stop() must run so the JVM is killed");
  });

  it("reports the server version from the initialize result", async () => {
    await mod.startClient();
    assert.ok(setServerVersion.calledWith("9.9.9"));
  });

  it("gives up on a start that never completes and kills its server", async () => {
    startTimeoutSeconds = 0.02;
    FakeClient.nextStart = () => new Promise(() => {}); // initialize never answered
    await assert.rejects(mod.startClient(), /did not start within/);
    const [hung] = FakeClient.instances;
    assert.ok(
      hung.serverProcess?.kill.calledOnce,
      "the hung JVM must be killed",
    );
    assert.strictEqual(
      (hung.clientOptions.errorHandler?.closed() as { action: number }).action,
      CloseAction.DoNotRestart,
      "the client must not respawn the server it was told to abandon",
    );
    assert.strictEqual(mod.getClient(), undefined);
  });

  it("keeps Stop and Restart usable after a start hangs", async () => {
    startTimeoutSeconds = 0.02;
    FakeClient.nextStart = () => new Promise(() => {});
    const hung = mod.startClient().catch(() => undefined);
    await mod.stopClient(); // queued behind the hung start; must not block
    await hung;
    startTimeoutSeconds = 120;
    await mod.restartClient();
    assert.strictEqual(FakeClient.instances.length, 2);
    assert.strictEqual(mod.getClient()?.state, State.Running);
  });

  it("lets the default error handler decide restarts for a healthy client", async () => {
    await mod.startClient();
    const [client] = FakeClient.instances;
    assert.strictEqual(
      (client.clientOptions.errorHandler?.closed() as { action: number })
        .action,
      CloseAction.Restart,
    );
  });

  describe("crash-restart argument refresh", () => {
    const runArgs = (c: FakeClient) =>
      (
        c.serverOptions as {
          run: { args: string[] };
          debug: { args: string[] };
        }
      ).run.args;

    it("rebuilds the shared args from current settings when a restart is chosen", async () => {
      await mod.startClient();
      const [client] = FakeClient.instances;
      const so = client.serverOptions as {
        run: { args: string[] };
        debug: { args: string[] };
      };
      assert.strictEqual(
        so.run.args,
        so.debug.args,
        "run and debug share one array",
      );
      const before = so.run.args;
      assert.ok(!before.includes("-Dnew=1"));

      serverVmArgs = ["-Dnew=1"];
      client.clientOptions.errorHandler?.closed();

      assert.strictEqual(
        so.run.args,
        before,
        "refreshed in place, not replaced",
      );
      assert.ok(so.debug.args.includes("-Dnew=1"));
      assert.deepStrictEqual(so.run.args.slice(-2), [
        "-jar",
        "/ext/server/gls.jar",
      ]);
    });

    it("does not rebuild when the handler decides not to restart", async () => {
      await mod.startClient();
      const [client] = FakeClient.instances;
      FakeClient.closedResult = () => ({ action: CloseAction.DoNotRestart });
      serverVmArgs = ["-Dnew=1"];
      client.clientOptions.errorHandler?.closed();
      assert.ok(!runArgs(client).includes("-Dnew=1"));
    });

    it("refreshes after an asynchronous handler result", async () => {
      await mod.startClient();
      const [client] = FakeClient.instances;
      FakeClient.closedResult = () =>
        Promise.resolve({ action: CloseAction.Restart });
      serverVmArgs = ["-Dasync=1"];
      const result = client.clientOptions.errorHandler?.closed();
      assert.ok(typeof (result as Promise<unknown>).then === "function");
      assert.deepStrictEqual(await result, { action: CloseAction.Restart });
      assert.ok(runArgs(client).includes("-Dasync=1"));
    });

    it("refreshes a thenable that is not a Promise", async () => {
      await mod.startClient();
      const [client] = FakeClient.instances;
      FakeClient.closedResult = () => ({
        then: (resolve: (v: unknown) => void) =>
          resolve({ action: CloseAction.Restart }),
      });
      serverVmArgs = ["-Dthenable=1"];
      await client.clientOptions.errorHandler?.closed();
      assert.ok(runArgs(client).includes("-Dthenable=1"));
    });

    it("never rebuilds an abandoned client's args", async () => {
      startTimeoutSeconds = 0.02;
      FakeClient.nextStart = () => new Promise(() => {});
      await assert.rejects(mod.startClient(), /did not start within/);
      const [hung] = FakeClient.instances;
      serverVmArgs = ["-Dnew=1"];
      hung.clientOptions.errorHandler?.closed();
      assert.ok(!runArgs(hung).includes("-Dnew=1"));
    });

    it("refreshes only the array of the client whose handler fired", async () => {
      await mod.startClient();
      const [first] = FakeClient.instances;
      await mod.restartClient();
      const second = FakeClient.instances[1];
      serverVmArgs = ["-Dold=1"];
      first.clientOptions.errorHandler?.closed();
      assert.ok(runArgs(first).includes("-Dold=1"));
      assert.ok(!runArgs(second).includes("-Dold=1"));
    });
  });

  it("waits indefinitely when the start timeout is 0", async () => {
    startTimeoutSeconds = 0;
    FakeClient.nextStart = () => new Promise((r) => setTimeout(r, 40));
    await mod.startClient();
    assert.strictEqual(mod.getClient()?.state, State.Running);
  });

  it("kills a server spawned only after its start timed out", async () => {
    startTimeoutSeconds = 0.02;
    FakeClient.spawnLate = true; // still resolving its working directory
    FakeClient.nextStart = () => new Promise(() => {});
    await assert.rejects(mod.startClient(), /did not start within/);
    const [late] = FakeClient.instances;
    const proc = { kill: sinon.stub() };
    late.serverProcess = proc;
    await new Promise((r) => setTimeout(r, 700));
    assert.ok(proc.kill.calledOnce, "the late JVM must be killed");
  });

  it("stops a timed-out client whose start completes later", async () => {
    startTimeoutSeconds = 0.02;
    FakeClient.spawnLate = true;
    const late = deferred();
    FakeClient.nextStart = () => late.promise;
    await assert.rejects(mod.startClient(), /did not start within/);
    const [client] = FakeClient.instances;
    late.resolve();
    await new Promise((r) => setTimeout(r, 10));
    assert.ok(client.stop.called, "an abandoned client must not keep running");
    assert.strictEqual(client.state, State.Stopped);
  });
});
