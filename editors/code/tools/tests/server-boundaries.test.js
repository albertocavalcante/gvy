// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const { EventEmitter } = require("node:events");
const fs = require("node:fs");
const https = require("node:https");
const os = require("node:os");
const path = require("node:path");
const { Readable } = require("node:stream");
const { test } = require("node:test");
const { validateJarFile } = require("../validate-server.js");
const { installVerifiedJar } = require("../prepare-server.js");
const { replaceLocalJar } = require("../source-install.js");

const GROOVY_CLASS = "org/codehaus/groovy/ast/ClassNode.class";

function jarBytes({ size = 5 * 1024 * 1024, name = GROOVY_CLASS } = {}) {
  const buffer = Buffer.alloc(size);
  buffer.writeUInt32LE(0x04034b50, 0);
  const centralOffset = size - 22 - 46 - Buffer.byteLength(name);
  buffer.writeUInt32LE(0x02014b50, centralOffset);
  buffer.writeUInt16LE(Buffer.byteLength(name), centralOffset + 28);
  buffer.write(name, centralOffset + 46);
  const endOffset = size - 22;
  buffer.writeUInt32LE(0x06054b50, endOffset);
  buffer.writeUInt16LE(1, endOffset + 10);
  buffer.writeUInt32LE(centralOffset, endOffset + 16);
  return buffer;
}

function jarFixture(t, bytes = jarBytes()) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "gvy-jar-boundary-"));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const jar = path.join(dir, "server.jar");
  fs.writeFileSync(jar, bytes);
  return { dir, jar };
}

function trackDescriptor(t) {
  const open = fs.openSync;
  const descriptors = [];
  t.mock.method(fs, "openSync", (...args) => {
    const fd = open(...args);
    descriptors.push(fd);
    return fd;
  });
  return () => {
    assert.ok(descriptors.length > 0, "validation must open the file");
    for (const fd of descriptors) {
      assert.throws(() => fs.fstatSync(fd), { code: "EBADF" });
    }
  };
}

test("installs verified original bytes when the source is replaced during validation", async (t) => {
  const bytes = jarBytes();
  const { dir, jar: source } = jarFixture(t, bytes);
  const dest = path.join(dir, "dest.jar");
  const replacement = path.join(dir, "replacement.jar");
  fs.writeFileSync(dest, "prior verified artifact");
  fs.writeFileSync(replacement, "unverified replacement");
  const expected = crypto.createHash("sha256").update(bytes).digest("hex");
  const open = fs.openSync;
  let replaced = false;
  t.mock.method(fs, "openSync", (...args) => {
    const fd = open(...args);
    if (!replaced) {
      replaced = true;
      fs.renameSync(replacement, source);
    }
    return fd;
  });

  await installVerifiedJar(source, dest, expected);

  assert.equal(replaced, true);
  assert.equal(
    crypto.createHash("sha256").update(fs.readFileSync(dest)).digest("hex"),
    expected,
  );
  assert.deepEqual(fs.readdirSync(dir), ["dest.jar"]);
});

for (const failure of ["checksum", "validation"]) {
  test(`preserves the prior artifact and cleans snapshots on ${failure} failure`, async (t) => {
    const bytes = jarBytes({ name: "another/Class.class" });
    const { dir, jar: source } = jarFixture(t, bytes);
    const dest = path.join(dir, "dest.jar");
    fs.writeFileSync(dest, "prior verified artifact");
    const expected =
      failure === "checksum"
        ? "0".repeat(64)
        : crypto.createHash("sha256").update(bytes).digest("hex");

    await assert.rejects(
      installVerifiedJar(source, dest, expected),
      failure === "checksum" ? /Checksum mismatch/ : /thin JAR detected/,
    );

    assert.equal(fs.readFileSync(dest, "utf8"), "prior verified artifact");
    assert.deepEqual(fs.readdirSync(dir), ["dest.jar"]);
  });
}

test("preserves the prior artifact when a substituted source cannot be removed", async (t) => {
  const bytes = jarBytes();
  const { dir, jar: source } = jarFixture(t, bytes);
  const dest = path.join(dir, "dest.jar");
  const previous = "prior verified artifact";
  fs.writeFileSync(dest, previous);
  const expected = crypto.createHash("sha256").update(bytes).digest("hex");

  await assert.rejects(
    installVerifiedJar(source, dest, expected, (candidate) => {
      validateJarFile(candidate);
      fs.unlinkSync(source);
      fs.mkdirSync(source);
    }),
  );

  assert.equal(
    crypto.createHash("sha256").update(fs.readFileSync(dest)).digest("hex"),
    crypto.createHash("sha256").update(previous).digest("hex"),
  );
  assert.equal(fs.statSync(source).isDirectory(), true);
  assert.deepEqual(fs.readdirSync(dir).sort(), ["dest.jar", "server.jar"]);
});

test("concurrent installs use distinct private snapshots and retain file permissions", async (t) => {
  const bytes = jarBytes();
  const { dir, jar: firstSource } = jarFixture(t, bytes);
  const secondSource = path.join(dir, "second.jar");
  fs.writeFileSync(secondSource, bytes);
  fs.chmodSync(firstSource, 0o640);
  const firstDest = path.join(dir, "first-dest.jar");
  const secondDest = path.join(dir, "second-dest.jar");
  const expected = crypto.createHash("sha256").update(bytes).digest("hex");
  const candidates = [];
  const validate = (candidate) => {
    candidates.push(candidate);
    assert.notEqual(candidate, firstSource);
    assert.notEqual(candidate, secondSource);
    if (process.platform !== "win32") {
      assert.equal(fs.statSync(path.dirname(candidate)).mode & 0o777, 0o700);
    }
    validateJarFile(candidate);
  };

  await Promise.all([
    installVerifiedJar(firstSource, firstDest, expected, validate),
    installVerifiedJar(secondSource, secondDest, expected, validate),
  ]);

  assert.equal(candidates.length, 2);
  assert.notEqual(path.dirname(candidates[0]), path.dirname(candidates[1]));
  assert.deepEqual(fs.readFileSync(firstDest), bytes);
  assert.deepEqual(fs.readFileSync(secondDest), bytes);
  if (process.platform !== "win32") {
    assert.equal(fs.statSync(firstDest).mode & 0o777, 0o640);
  }
  assert.deepEqual(fs.readdirSync(dir).sort(), [
    "first-dest.jar",
    "second-dest.jar",
  ]);
});

test("local-copy installation preserves the prior artifact when its source changes", (t) => {
  const bytes = jarBytes();
  const { dir, jar: source } = jarFixture(t, bytes);
  const dest = path.join(dir, "dest.jar");
  const replacement = path.join(dir, "replacement.jar");
  fs.writeFileSync(dest, "prior verified artifact");
  fs.writeFileSync(replacement, "unverified replacement");
  const open = fs.openSync;
  let replaced = false;
  t.mock.method(fs, "openSync", (...args) => {
    const fd = open(...args);
    if (!replaced) {
      replaced = true;
      fs.renameSync(replacement, source);
    }
    return fd;
  });

  assert.throws(
    () => replaceLocalJar(source, dest, validateJarFile),
    /Local JAR changed while copying/,
  );

  assert.equal(replaced, true);
  assert.equal(fs.readFileSync(dest, "utf8"), "prior verified artifact");
  assert.deepEqual(fs.readdirSync(dir).sort(), ["dest.jar", "server.jar"]);
});

test("validates the opened JAR when its path is replaced after opening", (t) => {
  const { dir, jar } = jarFixture(t);
  const replacement = path.join(dir, "replacement.jar");
  fs.writeFileSync(replacement, "replacement is not a ZIP");
  const open = fs.openSync;
  let replaced = false;
  t.mock.method(fs, "openSync", (...args) => {
    const fd = open(...args);
    if (args[0] === jar && !replaced) {
      replaced = true;
      fs.renameSync(replacement, jar);
    }
    return fd;
  });
  assert.doesNotThrow(() => validateJarFile(jar));
  assert.equal(replaced, true);
  assert.equal(fs.readFileSync(jar, "utf8"), "replacement is not a ZIP");
});

test("checks the size of the file actually opened after a path replacement", (t) => {
  const { dir, jar } = jarFixture(t);
  const replacement = path.join(dir, "replacement.jar");
  fs.writeFileSync(replacement, jarBytes({ size: 1024 }));
  const open = fs.openSync;
  let replaced = false;
  t.mock.method(fs, "openSync", (...args) => {
    if (args[0] === jar && !replaced) {
      replaced = true;
      fs.renameSync(replacement, jar);
    }
    return open(...args);
  });
  assert.throws(() => validateJarFile(jar), /JAR file is too small/);
  assert.equal(replaced, true);
});

test("rejects a directory substituted before the file is opened", (t) => {
  const { dir, jar } = jarFixture(t);
  const open = fs.openSync;
  let replaced = false;
  t.mock.method(fs, "openSync", (...args) => {
    if (args[0] === jar && !replaced) {
      replaced = true;
      fs.unlinkSync(jar);
      fs.mkdirSync(jar);
    }
    return open(...args);
  });
  assert.throws(() => validateJarFile(jar), /Path is not a regular file:/);
  assert.equal(replaced, true);
  assert.ok(fs.statSync(dir).isDirectory());
});

test("closes the opened regular JAR after successful validation", (t) => {
  const { jar } = jarFixture(t);
  const assertClosed = trackDescriptor(t);
  validateJarFile(jar);
  assertClosed();
});

for (const [label, bytes, error] of [
  ["invalid magic", Buffer.from("not a ZIP"), /invalid magic number/],
  ["thin JAR", jarBytes({ size: 1024 }), /JAR file is too small/],
  [
    "missing ZIP end signature",
    Buffer.concat([Buffer.from("PK\x03\x04"), Buffer.alloc(5 * 1024 * 1024)]),
    /no ZIP end signature/,
  ],
  [
    "missing Groovy dependency",
    jarBytes({ name: "another/Class.class" }),
    /thin JAR detected/,
  ],
]) {
  test(`closes the descriptor after rejecting ${label}`, (t) => {
    const { jar } = jarFixture(t, bytes);
    const assertClosed = trackDescriptor(t);
    assert.throws(() => validateJarFile(jar), error);
    assertClosed();
  });
}

test("closes the descriptor after an unexpected read failure", (t) => {
  const { jar } = jarFixture(t);
  const assertClosed = trackDescriptor(t);
  const failure = new Error("read failed");
  t.mock.method(fs, "readSync", () => {
    throw failure;
  });
  assert.throws(() => validateJarFile(jar), failure);
  assertClosed();
});

test("wraps ZIP inspection failures and closes the descriptor", (t) => {
  const bytes = jarBytes();
  bytes.writeUInt32LE(0, bytes.length - 22 - 46 - GROOVY_CLASS.length);
  const { jar } = jarFixture(t, bytes);
  const assertClosed = trackDescriptor(t);
  assert.throws(
    () => validateJarFile(jar),
    /Failed to inspect JAR content: Invalid central directory header/,
  );
  assertClosed();
});

test("retains missing-file and non-regular-file errors", (t) => {
  const { dir } = jarFixture(t);
  assert.throws(
    () => validateJarFile(path.join(dir, "missing.jar")),
    /JAR file not found:/,
  );
  const assertClosed = trackDescriptor(t);
  assert.throws(() => validateJarFile(dir), /Path is not a regular file:/);
  if (process.platform !== "win32") assertClosed();
});

test("retains the extension error and closes the descriptor", (t) => {
  const { dir, jar } = jarFixture(t);
  const other = path.join(dir, "server.zip");
  fs.renameSync(jar, other);
  const assertClosed = trackDescriptor(t);
  assert.throws(
    () => validateJarFile(other),
    /File does not have .jar extension:/,
  );
  assertClosed();
});

for (const [url, warns] of [
  ["https://api.github.com/download.jar", true],
  ["https://API.GITHUB.COM/download.jar", true],
  ["https://api.github.com.example.test/download.jar", false],
  ["https://example.test/api.github.com/download.jar", false],
  ["https://example.test/download.jar?host=api.github.com", false],
  ["https://api.github.com@example.test/download.jar", false],
]) {
  test(`rate-limit warning uses the parsed host of ${url}`, async (t) => {
    const http = require("../http.js");
    const tokens = require("../github-token.js");
    const stop = new Error("stop before downloading");
    t.mock.method(http, "downloadToFile", async () => {
      throw stop;
    });
    t.mock.method(tokens, "resolveGitHubToken", () => null);
    const warning = t.mock.method(console, "warn", () => {});
    t.mock.method(console, "log", () => {});
    const toolPath = require.resolve("../prepare-server.js");
    delete require.cache[toolPath];
    t.after(() => delete require.cache[toolPath]);
    const { downloadFromUrl } = require(toolPath);
    await assert.rejects(downloadFromUrl(url, "a".repeat(64)), stop);
    assert.equal(warning.mock.callCount(), warns ? 1 : 0);
    if (warns) {
      assert.match(warning.mock.calls[0].arguments[0], /may be rate-limited/);
    }
  });
}

test("GitHub authentication stays bound to the exact host across redirects", async (t) => {
  const seen = [];
  const urls = [
    "https://api.github.com/start",
    "https://api.github.com.example.test/redirect",
    "https://api.github.com/finish",
  ];
  t.mock.method(https, "get", (options, callback) => {
    seen.push(options);
    const next = urls[seen.length];
    const response = Object.assign(Readable.from([Buffer.from("ok")]), {
      statusCode: next ? 302 : 200,
      headers: next ? { location: next } : {},
    });
    callback(response);
    const request = new EventEmitter();
    request.setTimeout = () => request;
    return request;
  });
  const { fetchText } = require("../http.js");
  assert.equal(await fetchText(urls[0], { authToken: "test-token" }), "ok");
  assert.equal(seen.length, 3);
  assert.equal(seen[0].headers.Authorization, "Bearer test-token");
  assert.equal(seen[1].headers.Authorization, undefined);
  assert.equal(seen[2].headers.Authorization, "Bearer test-token");
});
