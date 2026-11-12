// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import * as fs from "fs";
import * as path from "path";

describe("hover engine contributions", () => {
  it("offers Native without exposing the retired Core hover engine", () => {
    const manifest = JSON.parse(
      fs.readFileSync(path.join(__dirname, "../../../../package.json"), "utf8"),
    );
    const engine =
      manifest.contributes.configuration.properties[
        "groovy.languageServer.engine"
      ];

    assert.deepStrictEqual(engine.enum, ["native"]);
    assert.strictEqual(engine.default, "native");
  });
});
