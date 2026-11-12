// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import * as sinon from "sinon";
import * as proxyquire from "proxyquire";
import type {
  DependenciesResult,
  DependencyService,
} from "../../../../src/features/dependencies/DependencyService";
import type { DependencyItem } from "../../../../src/features/dependencies/DependencyTreeProvider";

interface Provider {
  getChildren(): Promise<DependencyItem[]>;
  refresh(): void;
  dispose?(): void;
}

describe("DependencyTreeProvider request ownership", () => {
  let sandbox: sinon.SinonSandbox;
  let provider: Provider;
  let service: {
    getDependencies: sinon.SinonStub<
      [string],
      Promise<DependenciesResult | null>
    >;
  };
  let folders: { name: string; uri: { toString(): string } }[];
  let folderChanged: (() => void) | undefined;
  let disposeFolderListener: sinon.SinonSpy;
  let disposeEmitter: sinon.SinonSpy;
  let showErrorMessage: sinon.SinonStub;
  let logError: sinon.SinonStub;

  function result(version = "1", buildTool = "gradle"): DependenciesResult {
    return {
      buildTool,
      dependencies: [
        {
          name: "library",
          version,
          scope: "compile",
          path: "",
          isTransitive: false,
        },
      ],
    };
  }

  function labels(items: DependencyItem[]): string[] {
    return items.flatMap((item) => [
      item.label,
      ...(item.children ?? []).map((child) => child.label),
    ]);
  }

  beforeEach(() => {
    sandbox = sinon.createSandbox();
    logError = sandbox.stub(console, "error");
    sandbox.stub(console, "debug");
    folders = [{ name: "A", uri: { toString: () => "file:///workspace/A" } }];
    folderChanged = undefined;
    disposeFolderListener = sandbox.spy();
    disposeEmitter = sandbox.spy();
    showErrorMessage = sandbox.stub();
    service = {
      getDependencies: sandbox
        .stub<[string], Promise<DependenciesResult | null>>()
        .resolves(result()),
    };
    const vscode = {
      TreeItem: class {
        constructor(
          readonly label: string,
          readonly collapsibleState: number,
        ) {}
      },
      ThemeIcon: class {
        constructor(readonly id: string) {}
      },
      TreeItemCollapsibleState: { None: 0, Collapsed: 1 },
      EventEmitter: class {
        event = sandbox.stub();
        fire = sandbox.spy();
        dispose = disposeEmitter;
      },
      workspace: {
        get workspaceFolders() {
          return folders;
        },
        onDidChangeWorkspaceFolders: (listener: () => void) => {
          folderChanged = listener;
          return { dispose: disposeFolderListener };
        },
      },
      window: { showErrorMessage },
    };
    const module = proxyquire.noCallThru()(
      "../../../../src/features/dependencies/DependencyTreeProvider",
      { vscode },
    ) as {
      DependencyTreeProvider: new (
        service: Pick<DependencyService, "getDependencies">,
      ) => Provider;
    };
    provider = new module.DependencyTreeProvider(service);
  });

  afterEach(() => {
    provider.dispose?.();
    sandbox.restore();
  });

  it("shares twenty concurrent root reads per workspace", async () => {
    const pending = sinon.promise<DependenciesResult | null>();
    service.getDependencies.returns(pending);
    const reads = Array.from({ length: 20 }, () => provider.getChildren());
    assert.strictEqual(service.getDependencies.callCount, 1);
    pending.resolve(result());
    const values = await Promise.all(reads);
    values.forEach((items) =>
      assert.deepStrictEqual(labels(items), ["compile (1)", "library:1"]),
    );
  });

  for (const lateError of [false, true]) {
    it(`rejoins current results after refresh and late ${lateError ? "failure" : "success"}`, async () => {
      const old = sinon.promise<DependenciesResult | null>();
      const current = sinon.promise<DependenciesResult | null>();
      service.getDependencies.onFirstCall().returns(old);
      service.getDependencies.onSecondCall().returns(current);
      const oldRead = provider.getChildren();
      provider.refresh();
      const currentRead = provider.getChildren();
      current.resolve(result("2"));
      assert.deepStrictEqual(labels(await currentRead), [
        "compile (1)",
        "library:2",
      ]);
      if (lateError) old.reject(new Error("old connection failed"));
      else old.resolve(result("1"));
      assert.deepStrictEqual(labels(await oldRead), [
        "compile (1)",
        "library:2",
      ]);
      assert.deepStrictEqual(labels(await provider.getChildren()), [
        "compile (1)",
        "library:2",
      ]);
      assert.strictEqual(service.getDependencies.callCount, 2);
      sinon.assert.notCalled(showErrorMessage);
      sinon.assert.notCalled(logError);
    });
  }

  it("caches a known-tool empty result", async () => {
    service.getDependencies.resolves({ buildTool: "gradle", dependencies: [] });
    assert.deepStrictEqual(await provider.getChildren(), []);
    assert.deepStrictEqual(await provider.getChildren(), []);
    sinon.assert.calledOnce(service.getDependencies);
  });

  it("keeps the replacement read shared when the obsolete read settles first", async () => {
    const old = sinon.promise<DependenciesResult | null>();
    const current = sinon.promise<DependenciesResult | null>();
    service.getDependencies.onFirstCall().returns(old);
    service.getDependencies.onSecondCall().returns(current);
    const oldRead = provider.getChildren();
    provider.refresh();
    const currentRead = provider.getChildren();
    old.resolve(result("1"));
    await Promise.resolve();
    const thirdRead = provider.getChildren();
    assert.strictEqual(service.getDependencies.callCount, 2);
    current.resolve(result("2"));
    for (const items of await Promise.all([oldRead, currentRead, thirdRead])) {
      assert.deepStrictEqual(labels(items), ["compile (1)", "library:2"]);
    }
  });

  for (const unavailable of [
    null,
    { buildTool: "unknown", dependencies: [] },
  ]) {
    it(`retries ${unavailable === null ? "null" : "ambiguous unknown"} responses`, async () => {
      service.getDependencies.onFirstCall().resolves(unavailable);
      service.getDependencies.onSecondCall().resolves(result("2"));
      assert.deepStrictEqual(await provider.getChildren(), []);
      assert.deepStrictEqual(labels(await provider.getChildren()), [
        "compile (1)",
        "library:2",
      ]);
      sinon.assert.calledTwice(service.getDependencies);
    });
  }

  it("retries a current failure rather than caching it", async () => {
    service.getDependencies
      .onFirstCall()
      .rejects(new Error("connection failed"));
    service.getDependencies.onSecondCall().resolves(result());
    assert.deepStrictEqual(await provider.getChildren(), []);
    assert.deepStrictEqual(labels(await provider.getChildren()), [
      "compile (1)",
      "library:1",
    ]);
    sinon.assert.calledOnce(showErrorMessage);
    sinon.assert.calledTwice(service.getDependencies);
  });

  it("renders current workspace names and prefixes from cached data", async () => {
    await provider.getChildren();
    folders[0].name = "Renamed";
    folders.push({ name: "B", uri: { toString: () => "file:///workspace/B" } });
    assert.deepStrictEqual(labels(await provider.getChildren()), [
      "Renamed - compile (1)",
      "library:1",
      "B - compile (1)",
      "library:1",
    ]);
  });

  it("invalidates on workspace-folder changes", async () => {
    await provider.getChildren();
    service.getDependencies.resolves(result("2"));
    assert.ok(folderChanged);
    folderChanged();
    assert.deepStrictEqual(labels(await provider.getChildren()), [
      "compile (1)",
      "library:2",
    ]);
    sinon.assert.calledTwice(service.getDependencies);
  });

  for (const lateError of [false, true]) {
    it(`ignores late ${lateError ? "failure" : "success"} after disposal`, async () => {
      const pending = sinon.promise<DependenciesResult | null>();
      service.getDependencies.returns(pending);
      const read = provider.getChildren();
      assert.ok(provider.dispose);
      provider.dispose();
      if (lateError) pending.reject(new Error("disposed connection failed"));
      else pending.resolve(result());
      assert.deepStrictEqual(await read, []);
      assert.deepStrictEqual(await provider.getChildren(), []);
      sinon.assert.calledOnce(service.getDependencies);
      sinon.assert.calledOnce(disposeFolderListener);
      sinon.assert.calledOnce(disposeEmitter);
      sinon.assert.notCalled(showErrorMessage);
      sinon.assert.notCalled(logError);
    });
  }
});
