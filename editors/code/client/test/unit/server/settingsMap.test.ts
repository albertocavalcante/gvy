import * as assert from "assert";
import proxyquire from "proxyquire";

type Client = typeof import("../../../src/server/client");

describe("server settings map", () => {
  let configured: Record<string, unknown>;
  let client: Client;

  beforeEach(() => {
    configured = {};
    client = proxyquire.noCallThru()("../../../src/server/client", {
      vscode: {
        workspace: {
          getConfiguration: () => ({
            get: (key: string, fallback?: unknown) =>
              configured[key] ?? fallback,
          }),
        },
      },
      "vscode-languageclient/node": {},
      "../java/validator": {},
      "../ui/statusBar": {},
      "../configuration/settings": {},
      "../services/ServerResolver": {},
    });
  });

  it("sends the contributed default compilation cache limit", () => {
    assert.strictEqual(
      client.buildServerSettingsMap()["groovy.compilation.cacheMaxEntries"],
      300,
    );
  });

  it("reads the current compilation cache limit for startup and changes", () => {
    configured["compilation.cacheMaxEntries"] = 42;
    assert.strictEqual(
      client.buildServerSettingsMap()["groovy.compilation.cacheMaxEntries"],
      42,
    );

    configured["compilation.cacheMaxEntries"] = 12;
    assert.strictEqual(
      client.buildServerSettingsMap()["groovy.compilation.cacheMaxEntries"],
      12,
    );
  });
});
