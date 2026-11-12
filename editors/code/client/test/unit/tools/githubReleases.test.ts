// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";

import { checksumFromManifest } from "../../../../tools/github-releases.js";

const JAR_SHA =
  "552558bc588968c3127aa25114a86bb9137fa740572b39e7af44ff732a2802f2";
const VSIX_SHA =
  "613a6318e054ca5b72e18e3ea9131ece7b01972a7938f6531f686492484325c4";

describe("checksumFromManifest", () => {
  it("reads release manifests that prefix names with ./ (v0.4.7+)", () => {
    const manifest = `${JAR_SHA}  ./gls-0.4.8.jar\n${VSIX_SHA}  ./gvy-0.4.8.vsix\n`;
    assert.strictEqual(
      checksumFromManifest(manifest, "gls-0.4.8.jar"),
      JAR_SHA,
    );
    assert.strictEqual(
      checksumFromManifest(manifest, "gvy-0.4.8.vsix"),
      VSIX_SHA,
    );
  });

  it("reads bare names, binary-mode * names, and CRLF line endings", () => {
    assert.strictEqual(
      checksumFromManifest(`${JAR_SHA}  gls-0.4.5.jar\n`, "gls-0.4.5.jar"),
      JAR_SHA,
    );
    assert.strictEqual(
      checksumFromManifest(`${JAR_SHA} *gls-0.4.5.jar\r\n`, "gls-0.4.5.jar"),
      JAR_SHA,
    );
  });

  it("never matches a different asset that merely shares a suffix", () => {
    const manifest = `${JAR_SHA}  ./extra-gls-0.4.8.jar\n`;
    assert.strictEqual(checksumFromManifest(manifest, "gls-0.4.8.jar"), null);
  });

  it("ignores lines whose digest is not SHA-256", () => {
    assert.strictEqual(
      checksumFromManifest("abc123  ./gls-0.4.8.jar\n", "gls-0.4.8.jar"),
      null,
    );
  });
});
