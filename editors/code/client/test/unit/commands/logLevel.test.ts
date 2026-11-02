import * as assert from "assert";
import * as sinon from "sinon";
import proxyquire from "proxyquire";

describe("commands/logLevel", () => {
  function load(workspaceFolders: unknown[] | undefined) {
    const update = sinon.stub().resolves();
    const vscodeMock = {
      workspace: {
        workspaceFolders,
        getConfiguration: () => ({ get: () => "info", update }),
      },
      window: { showInformationMessage: sinon.stub().resolves(undefined) },
      commands: { executeCommand: sinon.stub() },
      ConfigurationTarget: { Global: 1, Workspace: 2 },
      "@global": true,
    };
    const mod = proxyquire.noCallThru()("../../../src/commands/logLevel", {
      vscode: vscodeMock,
    });
    return { mod, update, vscodeMock };
  }

  it("writes user settings when no folder is open", async () => {
    const { mod, update } = load(undefined);
    await mod.toggleDebugLogs();
    assert.deepStrictEqual(update.firstCall.args, [
      "server.logLevel",
      "debug",
      1,
    ]);
  });

  it("writes workspace settings when a folder is open", async () => {
    const { mod, update } = load([{ uri: {} }]);
    await mod.toggleDebugLogs();
    assert.deepStrictEqual(update.firstCall.args, [
      "server.logLevel",
      "debug",
      2,
    ]);
  });
});
