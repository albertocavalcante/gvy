// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { test } = require("node:test");
const { prepareRuntime, verifyRuntime } = require("../prepare-runtime");

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "gvy-runtime-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const source = path.join(root, "source");
  const destination = path.join(root, "runtime");
  fs.mkdirSync(source);
  const bytes = Buffer.from("pinned runtime bytes");
  const artifact = {
    group: "org.example",
    name: "groovy",
    version: "4.0.33",
    file: "groovy-4.0.33.jar",
    sha256: crypto.createHash("sha256").update(bytes).digest("hex"),
  };
  fs.writeFileSync(path.join(source, artifact.file), bytes);
  return {
    source,
    destination,
    manifest: { version: "4.0.33", artifacts: [artifact] },
  };
}

test("stages pinned local artifacts, metadata and upstream notices without network access", (t) => {
  const options = fixture(t);
  const readEntry = (_jar, entry) => Buffer.from(`upstream ${entry}`);
  prepareRuntime({ ...options, readEntry });
  verifyRuntime(options.destination, options.manifest);
  assert.equal(
    fs.readFileSync(
      path.join(options.destination, "groovy-4.0.33.jar"),
      "utf8",
    ),
    "pinned runtime bytes",
  );
  assert.match(
    fs.readFileSync(
      path.join(options.destination, "groovy-4.0.33.jar.NOTICE"),
      "utf8",
    ),
    /upstream META-INF\/NOTICE/,
  );
  assert.equal(
    JSON.parse(fs.readFileSync(path.join(options.destination, "manifest.json")))
      .version,
    "4.0.33",
  );
});

test("rejects changed or missing artifacts before making a bundle", (t) => {
  const options = fixture(t);
  fs.appendFileSync(
    path.join(options.source, options.manifest.artifacts[0].file),
    "changed",
  );
  assert.throws(() => prepareRuntime(options), /checksum/i);
  assert.equal(fs.existsSync(options.destination), false);
  fs.unlinkSync(path.join(options.source, options.manifest.artifacts[0].file));
  assert.throws(() => prepareRuntime(options), /not available locally/i);
});

test("revalidates packaged bytes rather than trusting a version marker", (t) => {
  const options = fixture(t);
  prepareRuntime({ ...options, readEntry: () => Buffer.from("license") });
  fs.appendFileSync(
    path.join(options.destination, "groovy-4.0.33.jar"),
    "corruption",
  );
  assert.throws(
    () => verifyRuntime(options.destination, options.manifest),
    /checksum/i,
  );
});

test("finds artifacts under the existing Gradle user home and rejects unsafe names", (t) => {
  const options = fixture(t);
  const artifact = options.manifest.artifacts[0];
  const cache = path.join(
    options.source,
    "caches/modules-2/files-2.1/org.example/groovy/4.0.33/hash",
  );
  fs.mkdirSync(cache, { recursive: true });
  fs.renameSync(
    path.join(options.source, artifact.file),
    path.join(cache, artifact.file),
  );
  prepareRuntime({
    ...options,
    source: undefined,
    gradleHome: options.source,
    readEntry: () => Buffer.from("notice"),
  });
  verifyRuntime(options.destination, options.manifest);
  artifact.file = "../outside.jar";
  assert.throws(() => prepareRuntime(options), /artifact name/i);
});
