// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

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

  it("uses compiled native hover by default and preserves an explicit engine", () => {
    assert.strictEqual(
      client.buildServerSettingsMap()["groovy.languageServer.engine"],
      "native",
    );

    configured["languageServer.engine"] = "core";
    assert.strictEqual(
      client.buildServerSettingsMap()["groovy.languageServer.engine"],
      "core",
    );
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

  it("forwards Gradle target classpaths, off unless the user enables them", () => {
    assert.strictEqual(
      client.buildServerSettingsMap()["groovy.gradle.targetClasspaths.enabled"],
      false,
    );

    configured["gradle.targetClasspaths.enabled"] = true;
    assert.strictEqual(
      client.buildServerSettingsMap()["groovy.gradle.targetClasspaths.enabled"],
      true,
    );
  });

  it("forwards the Gradle daemon idle timeout, ten minutes by default", () => {
    assert.strictEqual(
      client.buildServerSettingsMap()[
        "groovy.gradle.daemon.idleTimeoutMinutes"
      ],
      10,
    );

    configured["gradle.daemon.idleTimeoutMinutes"] = 3;
    assert.strictEqual(
      client.buildServerSettingsMap()[
        "groovy.gradle.daemon.idleTimeoutMinutes"
      ],
      3,
    );
  });
});
