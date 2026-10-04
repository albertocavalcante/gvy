import * as assert from "assert";

import {
  deriveSelection,
  requireChecksum,
} from "../../../../tools/prepare-server.js";

describe("server artifact selection", () => {
  it("selects the pinned release by default", () => {
    const names = [
      "GLS_TAG",
      "GLS_CHANNEL",
      "GLS_USE_PINNED",
      "USE_LATEST_GLS",
      "USE_LATEST_GROOVY_LSP",
    ] as const;
    const previous = names.map((name) => process.env[name]);
    for (const name of names) delete process.env[name];
    try {
      assert.deepStrictEqual(deriveSelection({}), { type: "pinned" });
    } finally {
      names.forEach((name, index) => {
        if (previous[index] === undefined) delete process.env[name];
        else process.env[name] = previous[index];
      });
    }
  });

  it("honors an explicit latest release request", () => {
    assert.deepStrictEqual(deriveSelection({ latest: true }), {
      type: "latest",
    });
  });

  it("rejects a downloaded artifact without a SHA-256 digest", () => {
    assert.throws(
      () => requireChecksum(null, "release JAR"),
      /SHA-256 checksum required/,
    );
    assert.throws(
      () => requireChecksum("not-a-digest", "release JAR"),
      /SHA-256 checksum required/,
    );
    assert.strictEqual(
      requireChecksum("a".repeat(64), "release JAR"),
      "a".repeat(64),
    );
  });
});
