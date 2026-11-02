import * as assert from "assert";
import * as fs from "fs";
import * as path from "path";
import proxyquire from "proxyquire";

type Settings = typeof import("../../../src/configuration/settings");

const settings: Settings = proxyquire.noCallThru()(
  "../../../src/configuration/settings",
  { vscode: { workspace: {} } },
);

function changeOf(...keys: string[]) {
  return {
    affectsConfiguration: (section: string) =>
      keys.some((k) => k === section || k.startsWith(`${section}.`)),
  };
}

const manifest = JSON.parse(
  fs.readFileSync(path.join(__dirname, "../../../../package.json"), "utf8"),
);
const contributed = Object.keys(manifest.contributes.configuration.properties)
  .filter((k) => k.startsWith("groovy."))
  .map((k) => k.slice("groovy.".length));

describe("configuration/settings", () => {
  it("classifies every contributed groovy.* setting exactly once", () => {
    const lists = [
      settings.RESTART_REQUIRED_SETTINGS,
      settings.SERVER_DYNAMIC_SETTINGS,
      settings.CLIENT_ONLY_SETTINGS,
    ];
    for (const key of contributed) {
      const owners = lists.filter((l) => l.includes(key)).length;
      assert.strictEqual(
        owners,
        1,
        `groovy.${key} must be in exactly one list`,
      );
    }
    for (const key of lists.flat()) {
      assert.ok(contributed.includes(key), `groovy.${key} is not contributed`);
    }
  });

  it("prompts for a restart when the parser engine changes", () => {
    assert.ok(
      settings.requiresServerRestart(changeOf("groovy.languageServer.engine")),
    );
  });

  it("prompts for a restart when the build strategy or log level changes", () => {
    assert.ok(
      settings.requiresServerRestart(changeOf("groovy.gradle.buildStrategy")),
    );
    assert.ok(
      settings.requiresServerRestart(changeOf("groovy.server.logLevel")),
    );
  });

  it("pushes CodeNarc changes without a restart", () => {
    const event = changeOf("groovy.codenarc.enabled");
    assert.ok(!settings.requiresServerRestart(event));
    assert.ok(settings.canBeAppliedDynamically(event));
  });

  it("does not bother the server with client-only settings", () => {
    const event = changeOf("groovy.statusBar.show");
    assert.ok(!settings.requiresServerRestart(event));
    assert.ok(!settings.canBeAppliedDynamically(event));
  });
});
