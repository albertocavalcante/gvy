import * as assert from "assert";

import {
  buildVersionRecord,
  describeRecord,
  parseVersionProperties,
  parseVersionRecord,
  provenanceFromProperties,
  requireFresh,
} from "../../../../tools/jar-provenance.js";

const STAMPED = [
  "version=0.5.4-SNAPSHOT",
  "baseVersion=0.5.4",
  "displayVersion=0.5.4-dev+g6c6f023.dirty",
  "gitSha=6c6f0238047441d3ef7f6f0d8dd98172892a383a",
  "gitDirty=true",
  "gitDirtyHash=d6fc16819240",
  "gitBranch=build/version-provenance",
  "ciRunUrl=https://github.com/o/r/actions/runs/1",
].join("\n");

describe("parseVersionProperties", () => {
  it("parses keys, values containing separators, and skips comments", () => {
    const props = parseVersionProperties(`# generated\n${STAMPED}\n\n! note\n`);
    assert.strictEqual(props.baseVersion, "0.5.4");
    assert.strictEqual(props.gitBranch, "build/version-provenance");
    assert.strictEqual(props.ciRunUrl, "https://github.com/o/r/actions/runs/1");
  });

  it("unescapes backslash sequences the build writes", () => {
    assert.strictEqual(
      parseVersionProperties("gitBranch=a\\\\b\\nc").gitBranch,
      "a\\b\nc",
    );
  });
});

describe("provenanceFromProperties", () => {
  it("reads display version, sha and dirty state", () => {
    assert.deepStrictEqual(
      provenanceFromProperties(parseVersionProperties(STAMPED)),
      {
        version: "0.5.4-dev+g6c6f023.dirty",
        gitSha: "6c6f0238047441d3ef7f6f0d8dd98172892a383a",
        dirty: true,
        dirtyHash: "d6fc16819240",
      },
    );
  });

  it("degrades to unknown for a pre-provenance jar", () => {
    assert.deepStrictEqual(
      provenanceFromProperties(parseVersionProperties("version=0.4.8")),
      { version: "0.4.8", gitSha: "unknown", dirty: null, dirtyHash: "" },
    );
  });
});

describe("version record", () => {
  const provenance = provenanceFromProperties(parseVersionProperties(STAMPED));

  it("round-trips through the file format and keeps the legacy tag", () => {
    const record = buildVersionRecord({
      tag: "local",
      source: "local",
      provenance,
    });
    assert.strictEqual(record.source, "local");
    assert.strictEqual(record.dirty, true);
    const parsed = parseVersionRecord(JSON.stringify(record));
    assert.strictEqual(parsed?.tag, "local");
    assert.strictEqual(parsed?.gitSha, record.gitSha);
  });

  it("records unknown provenance when the jar has none", () => {
    const record = buildVersionRecord({
      tag: "v0.4.8",
      source: "release",
      provenance: null,
    });
    assert.strictEqual(record.gitSha, "unknown");
    assert.strictEqual(record.dirty, null);
  });

  it("rejects an unknown source", () => {
    assert.throws(
      () =>
        buildVersionRecord({
          tag: "x",
          source: "magic" as "local",
          provenance: null,
        }),
      /Unknown server source/,
    );
  });

  it("reads a legacy plain-text marker and rejects garbage", () => {
    assert.deepStrictEqual(parseVersionRecord("v0.4.8\n"), { tag: "v0.4.8" });
    assert.strictEqual(parseVersionRecord("{oops"), null);
    assert.strictEqual(parseVersionRecord("  "), null);
  });

  it("describes a record on one line", () => {
    const line = describeRecord(
      buildVersionRecord({ tag: "local", source: "reused", provenance }),
    );
    assert.match(line, /0\.5\.4-dev\+g6c6f023\.dirty/);
    assert.match(line, /dirty d6fc16819240/);
    assert.match(line, /source=reused/);
  });
});

describe("requireFresh", () => {
  it("is opt-in", () => {
    assert.strictEqual(requireFresh({}), false);
    assert.strictEqual(requireFresh({ GLS_REQUIRE_FRESH: "0" }), false);
    assert.strictEqual(requireFresh({ GLS_REQUIRE_FRESH: "1" }), true);
    assert.strictEqual(requireFresh({ GLS_REQUIRE_FRESH: "TRUE" }), true);
  });
});
