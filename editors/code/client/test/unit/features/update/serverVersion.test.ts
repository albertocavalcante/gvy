// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { strict as assert } from "assert";
import {
  parseInstalledTag,
  resolveServerVersion,
} from "../../../../src/features/update/serverVersion";

describe("resolveServerVersion", () => {
  const readTag = () => "v0.4.8";

  it("prefers the version the running server reports", () => {
    assert.strictEqual(
      resolveServerVersion({
        reported: "0.5.1",
        serverPath: undefined,
        readInstalledTag: readTag,
      }),
      "0.5.1",
    );
  });

  it("falls back to the bundled server's installed tag before it starts", () => {
    assert.strictEqual(
      resolveServerVersion({
        reported: undefined,
        serverPath: undefined,
        readInstalledTag: readTag,
      }),
      "v0.4.8",
    );
  });

  it("does not attribute the bundled tag to a custom server JAR", () => {
    assert.strictEqual(
      resolveServerVersion({
        reported: undefined,
        serverPath: "/opt/gls.jar",
        readInstalledTag: readTag,
      }),
      "unknown",
    );
  });

  it("is unknown when the installed tag cannot be read", () => {
    assert.strictEqual(
      resolveServerVersion({
        reported: undefined,
        serverPath: "  ",
        readInstalledTag: () => {
          throw new Error("ENOENT");
        },
      }),
      "unknown",
    );
  });
});

describe("parseInstalledTag", () => {
  it("reads the tag from the JSON provenance record", () => {
    const record = JSON.stringify({
      version: "0.5.4-dev+g6c6f023",
      gitSha: "6c6f023",
      source: "local",
      tag: "local",
    });
    assert.strictEqual(parseInstalledTag(`${record}\n`), "local");
  });

  it("still reads a legacy plain-text tag", () => {
    assert.strictEqual(parseInstalledTag("v0.4.8\n"), "v0.4.8");
  });

  it("returns an empty tag for a corrupt record", () => {
    assert.strictEqual(parseInstalledTag("{not json"), "");
  });
});
