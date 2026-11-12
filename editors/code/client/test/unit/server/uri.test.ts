// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import { toProtocolUri } from "../../../src/server/uri";

describe("protocol URI conversion", () => {
  it("decodes the Windows drive separator in either hex case", () => {
    for (const separator of ["%3A", "%3a"]) {
      assert.strictEqual(
        toProtocolUri(`file:///C${separator}/work/test.groovy`, "win32"),
        "file:///C:/work/test.groovy",
      );
    }
  });

  it("keeps encoded path, query and fragment characters", () => {
    assert.strictEqual(
      toProtocolUri("file:///C%3A/work/a%3Ab.groovy?q=%3A#%3A", "win32"),
      "file:///C:/work/a%3Ab.groovy?q=%3A#%3A",
    );
    assert.strictEqual(
      toProtocolUri("file:///C:/work/a%3Ab.groovy", "win32"),
      "file:///C:/work/a%3Ab.groovy",
    );
  });

  it("preserves non-drive file paths, UNC shares and non-file schemes", () => {
    for (const uri of [
      "file:///work/a%3Ab.groovy",
      "file://server/share/a%3Ab.groovy",
      "untitled:Scratch%3A1",
      "git:///C%3A/work/test.groovy",
      "file:///C%3Aname.groovy",
    ]) {
      assert.strictEqual(toProtocolUri(uri, "win32"), uri);
    }
  });

  it("preserves URIs on other platforms", () => {
    const uri = "file:///C%3A/work/test.groovy";
    assert.strictEqual(toProtocolUri(uri, "darwin"), uri);
    assert.strictEqual(toProtocolUri(uri, "linux"), uri);
  });
});
