import * as assert from "assert";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

import { zip } from "./zipFixture";
import {
  buildVersionRecord,
  describeRecord,
  parseVersionProperties,
  assessLocalJar,
  readJarProvenance,
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
    const parsed = JSON.parse(JSON.stringify(record));
    assert.strictEqual(parsed.tag, "local");
    assert.strictEqual(parsed.gitSha, record.gitSha);
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

  it("describes a record on one line", () => {
    const line = describeRecord(
      buildVersionRecord({ tag: "local", source: "reused", provenance }),
    );
    assert.match(line, /0\.5\.4-dev\+g6c6f023\.dirty/);
    assert.match(line, /dirty d6fc16819240/);
    assert.match(line, /source=reused/);
  });
});

describe("describeRecord sanitizing", () => {
  it("strips control characters that came from inside a jar", () => {
    const line = describeRecord({
      version: "1.0\nFAKE LOG LINE",
      gitSha: "abc\u001b[31m",
      dirty: false,
      dirtyHash: "",
      source: "local",
      tag: "local",
    });
    assert.ok(!/\p{Cc}/u.test(line), JSON.stringify(line));
  });
});

describe("readJarProvenance", () => {
  let dir: string;
  beforeEach(() => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), "gls-prov-"));
  });
  afterEach(() => fs.rmSync(dir, { recursive: true, force: true }));

  it("reads version.properties from a real jar archive, as UTF-8", () => {
    const jar = path.join(dir, "gls.jar");
    fs.writeFileSync(
      jar,
      zip({
        "META-INF/MANIFEST.MF": Buffer.from("Manifest-Version: 1.0\n"),
        "version.properties": Buffer.from(
          `${STAMPED}\ngitBranch=caf\u00e9\n`,
          "utf8",
        ),
      }),
    );
    const provenance = readJarProvenance(jar);
    assert.strictEqual(provenance?.version, "0.5.4-dev+g6c6f023.dirty");
    assert.strictEqual(provenance?.dirty, true);
  });

  it("returns null for a jar without version.properties or a non-zip", () => {
    const jar = path.join(dir, "bare.jar");
    fs.writeFileSync(jar, zip({ "a.txt": Buffer.from("x") }));
    assert.strictEqual(readJarProvenance(jar), null);
    const junk = path.join(dir, "junk.jar");
    fs.writeFileSync(junk, "not a zip");
    assert.strictEqual(readJarProvenance(junk), null);
  });
});

describe("assessLocalJar", () => {
  const sha = "a".repeat(40);
  const jar = (over = {}) => ({
    version: "x",
    gitSha: sha,
    dirty: false,
    dirtyHash: "",
    ...over,
  });

  it("is fresh when the jar was built from HEAD on a clean tree", () => {
    assert.strictEqual(assessLocalJar(jar(), sha).state, "fresh");
  });
  it("is stale for another commit or no provenance", () => {
    assert.strictEqual(assessLocalJar(jar(), "b".repeat(40)).state, "stale");
    assert.strictEqual(assessLocalJar(null, sha).state, "stale");
    assert.strictEqual(
      assessLocalJar(jar({ gitSha: "unknown" }), sha).state,
      "stale",
    );
  });
  it("flags a dirty build of HEAD without calling it stale", () => {
    assert.strictEqual(
      assessLocalJar(jar({ dirty: true, dirtyHash: "abc" }), sha).state,
      "dirty",
    );
  });
  it("is stale when the checkout has uncommitted changes the clean jar lacks", () => {
    assert.strictEqual(assessLocalJar(jar(), sha, true).state, "stale");
    assert.strictEqual(assessLocalJar(jar(), sha, false).state, "fresh");
    assert.strictEqual(assessLocalJar(jar(), sha, null).state, "fresh");
  });
  it("cannot judge without a HEAD", () => {
    assert.strictEqual(assessLocalJar(jar(), null).state, "unverifiable");
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
