import * as assert from "assert";
import * as crypto from "node:crypto";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

import {
  deriveSelection,
  ensureFreshLocalJar,
  hasExplicitVersionSelection,
  installVerifiedJar,
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

  it("treats the legacy latest-release toggle as an explicit selection", () => {
    const previous = process.env.USE_LATEST_GROOVY_LSP;
    process.env.USE_LATEST_GROOVY_LSP = "true";
    try {
      assert.strictEqual(hasExplicitVersionSelection({}), true);
    } finally {
      if (previous === undefined) delete process.env.USE_LATEST_GROOVY_LSP;
      else process.env.USE_LATEST_GROOVY_LSP = previous;
    }
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

  it("normalizes an upper-case digest to lower case", () => {
    assert.strictEqual(requireChecksum("A".repeat(64), "x"), "a".repeat(64));
  });
});

const JAR_BYTES = Buffer.from("PK\x03\x04 fake jar");
// The real validator inspects JAR contents; these tests target the install step.
const accept = () => {};
const sha = (b: Buffer) => crypto.createHash("sha256").update(b).digest("hex");

describe("installVerifiedJar", () => {
  let dir: string;
  let dest: string;
  let source: string;

  beforeEach(() => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), "gls-test-"));
    dest = path.join(dir, "gls.jar");
    source = path.join(dir, ".gls-download.jar");
    fs.writeFileSync(dest, "previous good jar");
    fs.writeFileSync(source, JAR_BYTES);
  });

  afterEach(() => fs.rmSync(dir, { recursive: true, force: true }));

  it("installs a verified download over the previous jar", async () => {
    await installVerifiedJar(source, dest, sha(JAR_BYTES), accept);
    assert.deepStrictEqual(fs.readFileSync(dest), JAR_BYTES);
    assert.ok(!fs.existsSync(source), "temp file must be moved");
  });

  it("accepts an upper-case checksum", async () => {
    await installVerifiedJar(
      source,
      dest,
      sha(JAR_BYTES).toUpperCase(),
      accept,
    );
    assert.deepStrictEqual(fs.readFileSync(dest), JAR_BYTES);
  });

  it("keeps the previous jar and removes the download on checksum mismatch", async () => {
    await assert.rejects(
      installVerifiedJar(source, dest, "0".repeat(64), accept),
      /Checksum mismatch/,
    );
    assert.strictEqual(fs.readFileSync(dest, "utf8"), "previous good jar");
    assert.ok(!fs.existsSync(source));
  });

  it("refuses to install without a checksum", async () => {
    await assert.rejects(
      installVerifiedJar(source, dest, null, accept),
      /SHA-256 checksum required/,
    );
    assert.strictEqual(fs.readFileSync(dest, "utf8"), "previous good jar");
  });

  it("keeps the previous jar when the download is not a valid JAR", async () => {
    const reject = () => {
      throw new Error("thin JAR detected");
    };
    await assert.rejects(
      installVerifiedJar(source, dest, sha(JAR_BYTES), reject),
      /thin JAR/,
    );
    assert.strictEqual(fs.readFileSync(dest, "utf8"), "previous good jar");
    assert.ok(!fs.existsSync(source));
  });
});

describe("ensureFreshLocalJar", () => {
  const head = "a".repeat(40);
  const old = "b".repeat(40);
  const prov = (gitSha: string, dirty: boolean | null = false) => ({
    version: "v",
    gitSha,
    dirty,
    dirtyHash: "",
  });

  function deps(over: Record<string, unknown> = {}) {
    const warnings: string[] = [];
    const calls = { rebuild: 0 };
    const jars: Record<string, ReturnType<typeof prov>> = {
      "/old.jar": prov(old),
      "/new.jar": prov(head),
    };
    return {
      warnings,
      calls,
      deps: {
        readProvenance: (jar: string) => jars[jar] ?? null,
        getHead: () => head as string | null,
        strict: false,
        rebuild: () => {
          calls.rebuild += 1;
        },
        findJar: () => "/new.jar" as string | null,
        warn: (m: string) => warnings.push(m),
        ...over,
      },
    };
  }

  it("uses a fresh jar silently", () => {
    const t = deps();
    assert.strictEqual(ensureFreshLocalJar("/new.jar", t.deps), "/new.jar");
    assert.deepStrictEqual(t.warnings, []);
  });

  it("warns loudly but proceeds with a stale jar by default", () => {
    const t = deps();
    assert.strictEqual(ensureFreshLocalJar("/old.jar", t.deps), "/old.jar");
    assert.strictEqual(t.warnings.length, 1);
    assert.match(t.warnings[0], /out of date/);
    assert.strictEqual(t.calls.rebuild, 0);
  });

  it("rebuilds a stale jar under GLS_REQUIRE_FRESH", () => {
    const t = deps({ strict: true });
    assert.strictEqual(ensureFreshLocalJar("/old.jar", t.deps), "/new.jar");
    assert.strictEqual(t.calls.rebuild, 1);
  });

  it("fails a stale jar under GLS_REQUIRE_FRESH when it cannot rebuild", () => {
    const t = deps({ strict: true, rebuild: null });
    assert.throws(
      () => ensureFreshLocalJar("/old.jar", t.deps),
      /GLS_REQUIRE_FRESH.*stale/,
    );
  });

  it("fails when the rebuild is still stale", () => {
    const t = deps({ strict: true, findJar: () => "/old.jar" });
    assert.throws(() => ensureFreshLocalJar("/old.jar", t.deps), /still stale/);
  });

  it("only warns for a dirty build of HEAD, even when strict", () => {
    const t = deps({
      strict: true,
      readProvenance: () => prov(head, true),
    });
    assert.strictEqual(ensureFreshLocalJar("/x.jar", t.deps), "/x.jar");
    assert.strictEqual(t.warnings.length, 1);
    assert.strictEqual(t.calls.rebuild, 0);
  });

  it("does not judge when HEAD is unknown", () => {
    const t = deps({ strict: true, getHead: () => null });
    assert.strictEqual(ensureFreshLocalJar("/old.jar", t.deps), "/old.jar");
    assert.deepStrictEqual(t.warnings, []);
  });
});
