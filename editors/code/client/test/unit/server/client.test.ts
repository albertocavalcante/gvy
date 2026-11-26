// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import * as sinon from "sinon";
import { PassThrough } from "stream";
import * as path from "path";
import { createRequire } from "module";
import proxyquire from "proxyquire";
import {
  DocumentSymbolRequest,
  LSPErrorCodes,
  ResponseError,
} from "vscode-languageserver-protocol";

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

type TestDocument = { uri: string; version: number; isClosed: boolean };
type TestToken = {
  isCancellationRequested: boolean;
  onCancellationRequested?: (listener: () => void) => { dispose(): void };
};

class TestEvent<T> {
  readonly listeners = new Set<(value: T) => void>();
  readonly subscribe = (listener: (value: T) => void) => {
    this.listeners.add(listener);
    return { dispose: () => this.listeners.delete(listener) };
  };
  fire(value: T) {
    for (const listener of this.listeners) listener(value);
  }
}

interface FakeClientOptions {
  middleware?: {
    provideDocumentSymbols?: (
      document: TestDocument,
      token: TestToken,
      next: (document: TestDocument, token: TestToken) => Promise<unknown>,
    ) => Promise<unknown>;
    sendRequest?: (
      type: string | { method: string },
      params: unknown,
      token: { isCancellationRequested: boolean },
      next: SendRequest,
    ) => Promise<unknown>;
    didOpen: (
      document: unknown,
      next: (document: unknown) => Promise<void>,
    ) => Promise<void>;
    sendNotification: (
      type: string | { method: string },
      next: (
        type: string | { method: string },
        params?: unknown,
      ) => Promise<void>,
      params: unknown,
    ) => Promise<void>;
  };
  outputChannel?: unknown;
  stdioOptions?: { stdout: (input: PassThrough, channel: unknown) => void };
  errorHandler?: FakeErrorHandler;
}

type SendRequest = (
  type: string | { method: string },
  params: unknown,
  token: { isCancellationRequested: boolean },
) => Promise<unknown>;

class FakeClient {
  static instances: FakeClient[] = [];
  registerFeature = sinon.stub();
  sendNotification = sinon.stub().resolves();
  readonly stateEvent = new TestEvent<{ oldState: number; newState: number }>();
  readonly onDidChangeState = this.stateEvent.subscribe;
  // The pinned SDK keeps a single handler per wire method.
  readonly notifications = new Map<string, (params: unknown) => void>();
  readonly onNotification = sinon
    .stub()
    .callsFake((method: string, handler: (params: unknown) => void) => {
      this.notifications.set(method, handler);
      return { dispose: () => this.notifications.delete(method) };
    });
  status(quiescent: boolean) {
    this.notifications.get("groovy/status")?.({ health: "ok", quiescent });
  }
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
    setAnalysisUi: (ui: unknown) => void;
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
  const symbolUri = "untitled:Untitled-1";
  let openDocuments: TestDocument[];
  let edits: TestEvent<{ document: TestDocument }>;
  let closes: TestEvent<TestDocument>;
  let opens: TestEvent<TestDocument>;

  beforeEach(() => {
    edits = new TestEvent();
    closes = new TestEvent();
    opens = new TestEvent();
    FakeClient.instances = [];
    FakeClient.nextStart = undefined;
    FakeClient.spawnLate = false;
    startTimeoutSeconds = 120;
    serverVmArgs = undefined;
    openDocuments = [{ uri: symbolUri, version: 1, isClosed: false }];
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
        get textDocuments() {
          return openDocuments;
        },
        onDidChangeTextDocument: edits.subscribe,
        onDidCloseTextDocument: closes.subscribe,
        onDidOpenTextDocument: opens.subscribe,
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

  describe("document symbol transport", () => {
    const request = { textDocument: { uri: symbolUri } };
    const stale = new ResponseError(
      LSPErrorCodes.ContentModified,
      "Document changed",
    );
    const symbols = [{ name: "AfterGdsl", children: [{ name: "hello" }] }];

    function raw(
      next: SendRequest,
      token: TestToken,
      type: string | { method: string } = DocumentSymbolRequest.type,
    ) {
      const middleware = FakeClient.instances[0].clientOptions.middleware;
      return middleware?.sendRequest
        ? middleware.sendRequest(type, request, token, next)
        : next(type, request, token);
    }

    function provide(
      document: TestDocument,
      token: TestToken,
      next: (document: TestDocument, token: TestToken) => Promise<unknown>,
    ) {
      const middleware = FakeClient.instances[0].clientOptions.middleware;
      return middleware?.provideDocumentSymbols
        ? middleware.provideDocumentSymbols(document, token, next)
        : next(document, token);
    }

    async function send(
      next: SendRequest,
      token = { isCancellationRequested: false },
      type: string | { method: string } = DocumentSymbolRequest.type,
    ) {
      await mod.startClient();
      const document = openDocuments[0];
      return document
        ? provide(document, token, (_, forwarded) => raw(next, forwarded, type))
        : raw(next, token, type);
    }

    it("forwards a raw request without a symbol provider invocation only once", async () => {
      await mod.startClient();
      const next = sinon.stub().rejects(stale);
      await assert.rejects(
        raw(next, { isCancellationRequested: false }),
        (error) => error === stale,
      );
      assert.strictEqual(next.callCount, 1);
    });

    it("expires the provider binding when the SDK returns or fails", async () => {
      await mod.startClient();
      for (const fail of [false, true]) {
        const token = { isCancellationRequested: false };
        let transportToken = token;
        const pending = provide(
          openDocuments[0],
          token,
          async (_, forwarded) => {
            transportToken = forwarded;
            if (fail) throw stale;
            return symbols;
          },
        );
        if (fail) await assert.rejects(pending, (error) => error === stale);
        else assert.strictEqual(await pending, symbols);
        const next = sinon.stub().rejects(stale);
        await assert.rejects(
          raw(next, transportToken),
          (error) => error === stale,
        );
        assert.strictEqual(next.callCount, 1);
      }
    });

    it("does not retry across an edit while the SDK is flushing pending changes", async () => {
      await mod.startClient();
      const [document] = openDocuments;
      const gate = deferred();
      const next = sinon.stub().rejects(stale);
      const pending = provide(
        document,
        { isCancellationRequested: false },
        async (_, token) => {
          await gate.promise;
          return raw(next, token);
        },
      );
      document.version++;
      gate.resolve();
      await assert.rejects(pending, (error) => error === stale);
      assert.strictEqual(next.callCount, 1);
    });

    it("keeps overlapping providers separate after reopening the same URI and version", async () => {
      await mod.startClient();
      const [original] = openDocuments;
      const sharedToken = { isCancellationRequested: false };
      const oldGate = deferred();
      const newGate = deferred();
      const oldRpc = sinon.stub().rejects(stale);
      const newRpc = sinon.stub();
      newRpc.onFirstCall().rejects(stale);
      newRpc.onSecondCall().resolves(symbols);
      const first = provide(original, sharedToken, async (_, token) => {
        await oldGate.promise;
        return raw(oldRpc, token);
      });
      original.isClosed = true;
      const reopened = {
        uri: original.uri,
        version: original.version,
        isClosed: false,
      };
      openDocuments = [reopened];
      const second = provide(reopened, sharedToken, async (_, token) => {
        await newGate.promise;
        return raw(newRpc, token);
      });
      oldGate.resolve();
      newGate.resolve();
      const [firstResult, secondResult] = await Promise.allSettled([
        first,
        second,
      ]);
      assert.deepStrictEqual(firstResult, {
        status: "rejected",
        reason: stale,
      });
      assert.deepStrictEqual(secondResult, {
        status: "fulfilled",
        value: symbols,
      });
      assert.strictEqual(oldRpc.callCount, 1);
      assert.strictEqual(newRpc.callCount, 2);
    });

    it("discards a retry result when the document changes during SDK conversion", async () => {
      await mod.startClient();
      const next = sinon.stub();
      next.onFirstCall().rejects(stale);
      next.onSecondCall().resolves(symbols);
      const converted = deferred();
      const gate = deferred();
      const [document] = openDocuments;
      const pending = provide(
        document,
        { isCancellationRequested: false },
        async (_, token) => {
          const result = await raw(next, token);
          converted.resolve();
          await gate.promise;
          return result;
        },
      );
      await converted.promise;
      document.version++;
      gate.resolve();
      assert.strictEqual(await pending, null);
      assert.strictEqual(next.callCount, 2);
    });

    function sdkSymbolProvider(
      client: FakeClient,
      next: SendRequest,
      drain: () => Promise<void>,
    ) {
      // Use the SDK's document-symbol provider and BaseLanguageClient.sendRequest. Control the
      // open-notification drain and the wire, the same two asynchronous handoffs as the editor.
      const sdk = path.resolve(
        path.dirname(require.resolve("vscode-languageclient/node")),
        "../common",
      );
      // Scope the host API stubs to this SDK load, preserving the protocol error identity in other fixtures.
      const loadSdk = (
        createRequire(__filename)("proxyquire") as typeof proxyquire
      ).noCallThru();
      const feature = loadSdk(path.join(sdk, "documentSymbol.js"), {
        vscode: {
          languages: {
            registerDocumentSymbolProvider: () => ({ dispose() {} }),
          },
        },
        "./features": { TextDocumentLanguageFeature: class {} },
      }).DocumentSymbolFeature;
      const hostTypes = [
        "CallHierarchyItem",
        "CodeLens",
        "CompletionItem",
        "DocumentLink",
        "InlayHint",
        "SymbolInformation",
        "TypeHierarchyItem",
        "CodeAction",
        "Diagnostic",
        "CancellationError",
      ];
      const sdkClientClass = loadSdk(path.join(sdk, "client.js"), {
        vscode: {
          ...Object.fromEntries(hostTypes.map((name) => [name, class {}])),
          "@global": true,
        },
      }).BaseLanguageClient;
      const sdkClient = {
        middleware: client.clientOptions.middleware,
        _clientOptions: client.clientOptions,
        $start: async () => ({ sendRequest: next }),
        _didOpenTextDocumentFeature: {
          sendPendingOpenNotifications: drain,
        },
        _didChangeTextDocumentFeature: { syncKind: 0 },
        sendRequest: sdkClientClass.prototype.sendRequest,
        code2ProtocolConverter: { asDocumentSymbolParams: () => request },
        protocol2CodeConverter: {
          asDocumentSelector: () => [],
          asSymbolInformations: (result: unknown) => result,
        },
        handleFailedRequest: () => null,
      };
      const [, provider] = feature.prototype.registerLanguageProvider.call(
        { _client: sdkClient },
        {},
      );
      return provider as {
        provideDocumentSymbols(
          document: TestDocument,
          token: TestToken,
        ): Promise<unknown>;
      };
    }

    it("waits for server Ready before retrying a busy document symbol provider", async () => {
      await mod.startClient();
      const client = FakeClient.instances[0];
      const sync = deferred();
      const enteredSync = deferred();
      const firstRpc = deferred();
      const next = sinon.stub();
      next.onFirstCall().callsFake(async () => {
        firstRpc.resolve();
        throw stale;
      });
      next.onSecondCall().resolves(symbols);

      const provider = sdkSymbolProvider(client, next, async () => {
        enteredSync.resolve();
        await sync.promise;
      });
      const pending = provider.provideDocumentSymbols(openDocuments[0], {
        isCancellationRequested: false,
        onCancellationRequested: new TestEvent<void>().subscribe,
      });
      await enteredSync.promise;
      client.status(false);
      sync.resolve();
      await firstRpc.promise;
      await new Promise<void>((resolve) => setImmediate(resolve));
      assert.strictEqual(
        next.callCount,
        1,
        "a second RPC spent while startup is still busy cannot recover the outline",
      );
      client.status(false);
      client.status(true);
      assert.deepStrictEqual(await pending, symbols);
      assert.strictEqual(next.callCount, 2);
      assert.strictEqual(
        edits.listeners.size + closes.listeners.size + opens.listeners.size,
        0,
      );
    });

    it("delivers a successful first response while busy without waiting for Ready", async () => {
      await mod.startClient();
      FakeClient.instances[0].status(false);
      const next = sinon.stub().resolves(symbols);
      assert.strictEqual(await send(next), symbols);
      assert.strictEqual(next.callCount, 1);
    });

    it("forgets the previous busy period when the SDK starts another connection", async () => {
      await mod.startClient();
      const client = FakeClient.instances[0];
      client.status(false);
      client.stateEvent.fire({
        oldState: State.Running,
        newState: State.Starting,
      });
      client.stateEvent.fire({
        oldState: State.Starting,
        newState: State.Running,
      });
      const next = sinon.stub();
      next.onFirstCall().rejects(stale);
      next.onSecondCall().resolves(symbols);
      assert.strictEqual(await send(next), symbols);
      assert.strictEqual(next.callCount, 2);
    });

    for (const change of [
      "edit",
      "close",
      "reopen",
      "cancel",
      "stop",
      "restart",
      "connection restart",
    ]) {
      it(`releases a busy symbol waiter after ${change} without Ready`, async () => {
        await mod.startClient();
        const client = FakeClient.instances[0];
        client.status(false);
        const cancel = new TestEvent<void>();
        const token = {
          isCancellationRequested: false,
          onCancellationRequested: cancel.subscribe,
        };
        const next = sinon.stub();
        next.onFirstCall().rejects(stale);
        next.onSecondCall().resolves(symbols);
        const pending = send(next, token);
        const result = pending.then(
          (value) => value,
          (error: unknown) => error,
        );
        await new Promise<void>((resolve) => setImmediate(resolve));
        assert.strictEqual(next.callCount, 1);
        const document = openDocuments[0];
        switch (change) {
          case "edit":
            document.version++;
            edits.fire({ document });
            break;
          case "close":
            document.isClosed = true;
            closes.fire(document);
            break;
          case "reopen":
            openDocuments = [{ ...document }];
            closes.fire(document);
            opens.fire(openDocuments[0]);
            break;
          case "cancel":
            token.isCancellationRequested = true;
            cancel.fire();
            break;
          case "stop":
            await mod.stopClient();
            break;
          case "restart":
            await mod.restartClient();
            break;
          case "connection restart":
            client.state = State.Stopped;
            client.stateEvent.fire({
              oldState: State.Running,
              newState: State.Stopped,
            });
            client.state = State.Running;
            client.stateEvent.fire({
              oldState: State.Stopped,
              newState: State.Running,
            });
            break;
        }
        assert.strictEqual(await result, stale);
        client.status(true);
        assert.strictEqual(next.callCount, 1);
        assert.strictEqual(
          edits.listeners.size +
            closes.listeners.size +
            opens.listeners.size +
            cancel.listeners.size,
          0,
        );
      });
    }

    it("sends one fresh RPC after ContentModified for an unchanged open document", async () => {
      const next = sinon.stub();
      next.onFirstCall().rejects(stale);
      next.onSecondCall().resolves(symbols);
      const token = { isCancellationRequested: false };
      assert.strictEqual(await send(next, token), symbols);
      assert.strictEqual(next.callCount, 2);
      for (const call of next.getCalls()) {
        assert.deepStrictEqual(call.args, [
          DocumentSymbolRequest.type,
          request,
          token,
        ]);
      }
    });

    it("propagates a second ContentModified without sending a third request", async () => {
      const second = new ResponseError(
        LSPErrorCodes.ContentModified,
        "Retired again",
      );
      const next = sinon.stub();
      next.onFirstCall().rejects(stale);
      next.onSecondCall().rejects(second);
      await assert.rejects(send(next), (error) => error === second);
      assert.strictEqual(next.callCount, 2);
    });

    it("preserves an empty success and never retries a closed document", async () => {
      const empty: unknown[] = [];
      const next = sinon.stub().resolves(empty);
      assert.strictEqual(await send(next), empty);
      openDocuments = [];
      next.rejects(stale);
      await assert.rejects(send(next), (error) => error === stale);
      assert.strictEqual(next.callCount, 2);
    });

    for (const change of ["edit", "close", "reopen", "cancel", "restart"]) {
      it(`does not retry after ${change}`, async () => {
        const token = { isCancellationRequested: false };
        const next = sinon.stub().callsFake(async () => {
          if (change === "edit") openDocuments[0].version++;
          if (change === "close") openDocuments[0].isClosed = true;
          if (change === "reopen") openDocuments = [{ ...openDocuments[0] }];
          if (change === "cancel") token.isCancellationRequested = true;
          if (change === "restart") await mod.restartClient();
          throw stale;
        });
        await assert.rejects(send(next, token), (error) => error === stale);
        assert.strictEqual(next.callCount, 1);
      });
    }

    it("does not deliver a retry result after an edit", async () => {
      const next = sinon.stub();
      next.onFirstCall().rejects(stale);
      next.onSecondCall().callsFake(async () => {
        openDocuments[0].version++;
        return symbols;
      });
      await assert.rejects(send(next), (error) => error === stale);
      assert.strictEqual(next.callCount, 2);
    });

    it("forwards other request errors without retry", async () => {
      const failure = new Error("transport disconnected");
      const next = sinon.stub().rejects(failure);
      await assert.rejects(send(next), (error) => error === failure);
      assert.strictEqual(next.callCount, 1);
      next.resetBehavior();
      next.rejects(stale);
      await assert.rejects(
        send(next, undefined, "textDocument/hover"),
        (error) => error === stale,
      );
      assert.strictEqual(next.callCount, 2);
    });
  });

  it("opens legacy documents immediately without a session notification", async () => {
    mod.setAnalysisUi({ setClient: () => {}, beginSession: () => "session-1" });
    await mod.startClient();
    const instance = FakeClient.instances[0];
    const events: string[] = [];
    const document = { uri: { toString: () => "file:///a.groovy" } };
    const pending = instance.clientOptions.middleware!.didOpen(
      document,
      async () => {
        events.push("open");
      },
    );
    assert.deepStrictEqual(events, ["open"]);
    await pending;
    assert.strictEqual(instance.sendNotification.callCount, 0);
  });

  it("enqueues each supported session and open before a later request", async () => {
    let sequence = 0;
    mod.setAnalysisUi({
      setClient: () => {},
      beginSession: () => `session-${++sequence}`,
    });
    await mod.startClient();
    const instance = FakeClient.instances[0];
    instance.initializeResult = {
      capabilities: { experimental: { groovyDocumentAnalysis: true } },
    };
    const middleware = instance.clientOptions.middleware!;
    const document = { uri: { toString: () => "file:///a.groovy" } };
    const open = { textDocument: { uri: "file:///a.groovy" } };
    const gate = deferred();
    const events: string[] = [];
    const raw = (type: string | { method: string }, params?: unknown) => {
      const method = typeof type === "string" ? type : type.method;
      events.push(
        method === "groovy/documentAnalysisSession"
          ? (params as { sessionId: string }).sessionId
          : method,
      );
      return method === "groovy/documentAnalysisSession"
        ? gate.promise
        : Promise.resolve();
    };
    const next = () =>
      middleware.sendNotification(
        { method: "textDocument/didOpen" },
        raw,
        open,
      );
    const first = middleware.didOpen(document, next);
    events.push("request");
    assert.deepStrictEqual(events, [
      "session-1",
      "textDocument/didOpen",
      "request",
    ]);
    gate.resolve();
    await first;
    await middleware.sendNotification(
      { method: "textDocument/didClose" },
      raw,
      { textDocument: { uri: "file:///a.groovy" } },
    );
    await middleware.didOpen(document, next);
    assert.deepStrictEqual(events.slice(-3), [
      "textDocument/didClose",
      "session-2",
      "textDocument/didOpen",
    ]);
  });

  it("discards an unforwarded session when opening fails", async () => {
    let sequence = 0;
    mod.setAnalysisUi({
      setClient: () => {},
      beginSession: () => `session-${++sequence}`,
    });
    await mod.startClient();
    const instance = FakeClient.instances[0];
    instance.initializeResult = {
      capabilities: { experimental: { groovyDocumentAnalysis: true } },
    };
    const middleware = instance.clientOptions.middleware!;
    const document = { uri: { toString: () => "file:///a.groovy" } };
    await assert.rejects(
      middleware.didOpen(document, async () => {
        throw new Error("open failed");
      }),
    );
    const sent: string[] = [];
    const raw = async (type: string | { method: string }, params?: unknown) => {
      const method = typeof type === "string" ? type : type.method;
      sent.push(
        method === "groovy/documentAnalysisSession"
          ? (params as { sessionId: string }).sessionId
          : method,
      );
    };
    await middleware.didOpen(document, () =>
      middleware.sendNotification({ method: "textDocument/didOpen" }, raw, {
        textDocument: { uri: "file:///a.groovy" },
      }),
    );
    assert.deepStrictEqual(sent, ["session-2", "textDocument/didOpen"]);
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

  it("bounds auxiliary stdout through the configured language-client adapter", async () => {
    await mod.startClient();
    const stdout = new PassThrough();
    const info = sinon.stub();
    const error = sinon.stub();
    FakeClient.instances[0].clientOptions.stdioOptions!.stdout(stdout, {
      info,
      error,
    });
    stdout.end("x".repeat(64 * 1024 + 1));
    await new Promise((resolve) => stdout.on("close", resolve));
    assert.strictEqual(info.callCount, 1);
    assert.match(info.firstCall.args[0], /truncated/);
    assert.ok(Buffer.byteLength(info.firstCall.args[0]) < 64 * 1024 + 128);
    assert.strictEqual(error.callCount, 0);
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
