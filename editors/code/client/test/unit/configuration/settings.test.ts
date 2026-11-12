// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

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

  it("pushes compilation cache limit changes without a restart", () => {
    const event = changeOf("groovy.compilation.cacheMaxEntries");
    assert.ok(!settings.requiresServerRestart(event));
    assert.ok(settings.canBeAppliedDynamically(event));
  });

  it("does not bother the server with client-only settings", () => {
    const event = changeOf("groovy.statusBar.show");
    assert.ok(!settings.requiresServerRestart(event));
    assert.ok(!settings.canBeAppliedDynamically(event));
  });
});

describe("configuration/settings start timeout", () => {
  function timeoutFor(value: unknown): number {
    const mod: Settings = proxyquire.noCallThru()(
      "../../../src/configuration/settings",
      {
        vscode: {
          workspace: {
            getConfiguration: () => ({
              get: (key: string) =>
                key === "server.startTimeoutSeconds" ? value : undefined,
            }),
          },
        },
      },
    );
    return mod.getConfiguration().startTimeoutSeconds;
  }

  it("defaults to the manifest default when unset or invalid", () => {
    const fallback =
      manifest.contributes.configuration.properties[
        "groovy.server.startTimeoutSeconds"
      ].default;
    assert.strictEqual(timeoutFor(undefined), fallback);
    assert.strictEqual(timeoutFor(-5), fallback);
    assert.strictEqual(timeoutFor("30"), fallback);
  });

  it("treats timeouts beyond setTimeout's range as no limit", () => {
    // Node clamps delays above 2^31-1 ms to 1 ms, which would kill every start.
    assert.strictEqual(timeoutFor(1e7), 0);
  });

  it("keeps 0 (no limit) and positive values", () => {
    assert.strictEqual(timeoutFor(0), 0);
    assert.strictEqual(timeoutFor(45), 45);
  });
});
