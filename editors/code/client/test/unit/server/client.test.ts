import * as assert from "assert";
import * as sinon from "sinon";
import proxyquire from "proxyquire";

type Deferred = { promise: Promise<void>; resolve: () => void; reject: (e: Error) => void };
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

interface FakeClientOptions {
  outputChannel?: unknown;
}

class FakeClient {
  static instances: FakeClient[] = [];
  static nextStart: (() => Promise<void>) | undefined;
  state = State.Stopped;
  initializeResult: unknown = { serverInfo: { name: "gls", version: "9.9.9" } };
  stop = sinon.stub().callsFake(async () => {
    if (this.state !== State.Running) {
      throw new Error(`Client is not running and can't be stopped. It's current state is: ${this.state}`);
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

  beforeEach(() => {
    FakeClient.instances = [];
    FakeClient.nextStart = undefined;
    watchers = [];
    validateJava = sinon.stub().resolves({
      isValid: true,
      resolution: { path: "/jdk", version: 21, source: "setting" },
    });
    showJavaError = sinon.stub().resolves();
    setServerVersion = sinon.stub();
    createOutputChannel = sinon.stub().callsFake(() => ({ dispose: sinon.stub() }));

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
      "vscode-languageclient/node": { LanguageClient: FakeClient, State },
      "../java/validator": {
        validateJava,
        showJavaError,
        getJavaExecutable: () => "/jdk/bin/java",
      },
      "../ui/statusBar": {
        setClient: sinon.stub(),
        getStatusBarManager: () => ({ setOutputChannel: sinon.stub(), setServerVersion }),
      },
      "../configuration/settings": {
        getConfiguration: () => ({ serverPath: undefined }),
      },
      "../services/ServerResolver": {
        ServerResolver: class {
          async resolve() {
            return "/ext/server/gls.jar";
          }
        },
      },
    });
    mod.initializeClient({ subscriptions: [], asAbsolutePath: (p: string) => p });
  });

  it("serializes restart behind an in-flight start (no second server)", async () => {
    const firstStart = deferred();
    FakeClient.nextStart = () => firstStart.promise;
    const starting = mod.startClient();
    const restarting = mod.restartClient();

    await new Promise((r) => setTimeout(r, 0));
    assert.strictEqual(FakeClient.instances.length, 1, "restart must wait for the start");

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
    assert.strictEqual(FakeClient.instances[1].clientOptions.outputChannel, channel);
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
    validateJava.resolves({ isValid: false, error: "No Java installation found" });
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
});
