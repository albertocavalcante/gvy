// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict";
import test, { mock } from "node:test";
import fs from "node:fs/promises";
import { syncBuiltinESMExports } from "node:module";
import { mkdtemp, writeFile, unlink, rmdir, mkdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createHash } from "node:crypto";
import {
  command,
  fileIdentity,
  readJson,
  writeJson,
  configurationSnapshot,
} from "./io.mjs";

// Exercise the exact check/use boundary without relying on scheduler timing.
async function afterMetadata(path, action, run) {
  const canonical = await fs.realpath(path);
  const originalStat = fs.stat;
  const originalOpen = fs.open;
  let fired = false;
  const handles = [];
  const intercept = async (metadata) => {
    if (!fired) {
      fired = true;
      await action();
    }
    return metadata;
  };
  mock.method(fs, "stat", async (target, ...args) => {
    const result = await originalStat(target, ...args);
    return target === path || target === canonical ? intercept(result) : result;
  });
  mock.method(fs, "open", async (target, ...args) => {
    const handle = await originalOpen(target, ...args);
    if (target === path || target === canonical) {
      handles.push(handle);
      const stat = handle.stat.bind(handle);
      handle.stat = async (...options) => intercept(await stat(...options));
    }
    return handle;
  });
  syncBuiltinESMExports();
  try {
    await run();
    assert.equal(fired, true, "The mutation must reach the metadata boundary");
    for (const handle of handles)
      assert.equal(
        handle.fd,
        -1,
        "Read descriptors must close on success or failure",
      );
  } finally {
    mock.restoreAll();
    syncBuiltinESMExports();
  }
}

test("JSON reading stays on the checked descriptor across pathname replacement", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gvy-json-replace-"));
  const path = join(directory, "input.json");
  try {
    await writeFile(path, '{"original":true}');
    await afterMetadata(
      path,
      async () => {
        await fs.rename(path, `${path}.old`);
        await writeFile(path, '{"substituted":true}');
      },
      async () => assert.deepEqual(await readJson(path), { original: true }),
    );
  } finally {
    await rm(directory, { recursive: true });
  }
});

test("JSON reading rejects growth past its byte limit after metadata inspection", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gvy-json-growth-"));
  const path = join(directory, "input.json");
  try {
    await writeFile(path, "{}");
    await afterMetadata(
      path,
      () => fs.appendFile(path, " ".repeat(1024 * 1024)),
      () => assert.rejects(readJson(path), /limit|changed|oversized/i),
    );
  } finally {
    await rm(directory, { recursive: true });
  }
});

test("JSON input refuses symlinks and output rejects an unreadable oversized receipt", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gvy-json-policy-"));
  const path = join(directory, "input.json");
  try {
    await writeFile(path, "{}");
    await fs.symlink(path, `${path}.link`);
    await assert.rejects(readJson(`${path}.link`), /ELOOP|symbolic|symlink/i);
    await assert.rejects(
      writeJson(join(directory, "large.json"), {
        data: "x".repeat(1024 * 1024),
      }),
      /limit|oversized/i,
    );
    assert.deepEqual((await fs.readdir(directory)).sort(), [
      "input.json",
      "input.json.link",
    ]);
  } finally {
    await rm(directory, { recursive: true });
  }
});

test("JSON byte boundaries preserve large legitimate input and reject malformed UTF-8 and special files", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gvy-json-boundary-"));
  const path = join(directory, "input.json");
  try {
    for (const size of [
      1024 * 1024 - 1,
      1024 * 1024,
      1024 * 1024 + 1,
      10 * 1024 * 1024,
    ]) {
      await writeFile(path, '"' + "x".repeat(size - 2) + '"');
      if (size <= 1024 * 1024)
        assert.equal((await readJson(path)).length, size - 2);
      else await assert.rejects(readJson(path), /oversized/);
    }
    await writeFile(path, Buffer.from([34, 255, 34]));
    await assert.rejects(readJson(path), /encoded data|encoding/i);
    await assert.rejects(readJson(directory), /regular file/);
    const fifo = join(directory, "input.fifo");
    assert.equal((await command(["mkfifo", fifo])).code, 0);
    const fifoCheck = await command(
      [
        process.execPath,
        "--input-type=module",
        "-e",
        `import { readJson } from ${JSON.stringify(new URL("./io.mjs", import.meta.url).href)};
       try { await readJson(process.argv[1]); process.exitCode=2; }
       catch(error) { if(!/regular file/.test(error.message)) throw error; }`,
        fifo,
      ],
      { timeoutMs: 2000 },
    );
    assert.equal(fifoCheck.code, 0, fifoCheck.stderr);
    await assert.rejects(
      writeJson(join(directory, "oversized.json"), {
        data: "x".repeat(1024 * 1024),
      }),
      /byte limit/,
    );
    assert.equal(
      (await fs.readdir(directory)).some((name) => name.endsWith(".pending")),
      false,
    );
  } finally {
    await rm(directory, { recursive: true });
  }
});

test("configuration fingerprint growth uses its one MiB limit on the same descriptor", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gvy-config-growth-"));
  const path = join(directory, "gradle.properties");
  try {
    await writeFile(path, "original");
    await afterMetadata(
      path,
      () => fs.appendFile(path, "x".repeat(1024 * 1024)),
      () =>
        assert.rejects(
          configurationSnapshot(directory, join(directory, ".m2")),
          /byte limit/,
        ),
    );
  } finally {
    await rm(directory, { recursive: true });
  }
});

test("artifact hashes follow the opened inode and retain explicit symlink selection support", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gvy-hash-replace-"));
  const path = join(directory, "artifact");
  try {
    await writeFile(path, "original");
    await fs.symlink(path, `${path}.link`);
    await afterMetadata(
      path,
      async () => {
        await fs.rename(path, `${path}.old`);
        await writeFile(path, "replaced");
      },
      async () => {
        const identity = await fileIdentity(`${path}.link`);
        assert.equal(identity.bytes, 8);
        assert.equal(
          identity.sha256,
          createHash("sha256").update("original").digest("hex"),
        );
      },
    );
  } finally {
    await rm(directory, { recursive: true });
  }
});

test("failed atomic JSON publication removes only its owned pending file", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gvy-json-publish-"));
  const path = join(directory, "destination");
  try {
    await mkdir(path);
    await assert.rejects(writeJson(path, { data: true }), /EISDIR|EPERM/);
    assert.deepEqual(await fs.readdir(directory), ["destination"]);
    await writeFile(`${path}.pending`, "existing owner");
    await assert.rejects(writeJson(path, {}), /EEXIST/);
    assert.equal(
      await fs.readFile(`${path}.pending`, "utf8"),
      "existing owner",
    );
  } finally {
    await rm(directory, { recursive: true });
  }
});

test("argv metacharacters are literal and output/deadlines are bounded", async () => {
  const literal = "$(touch /should-never-exist); `echo injected`";
  const result = await command([
    process.execPath,
    "-e",
    "process.stdout.write(process.argv[1])",
    literal,
  ]);
  assert.equal(result.code, 0);
  assert.equal(result.stdout, literal);
  await assert.rejects(
    command(
      [process.execPath, "-e", 'process.stdout.write("x".repeat(1024))'],
      { maxBytes: 100 },
    ),
    /output exceeds/,
  );
  await assert.rejects(
    command([process.execPath, "-e", "setInterval(() => {}, 1000)"], {
      timeoutMs: 50,
    }),
    /deadline exceeded/,
  );
});

test("artifact fingerprints and private atomic JSON receipts preserve exact bytes", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gvy-qualification-test-"));
  const path = join(directory, "receipt.json");
  try {
    await writeJson(path, { result: "unqualified" });
    assert.deepEqual(await readJson(path), { result: "unqualified" });
    const before = await fileIdentity(path);
    await writeFile(path, "different");
    const after = await fileIdentity(path);
    assert.notEqual(before.sha256, after.sha256);
    assert.equal(after.bytes, 9);
  } finally {
    await unlink(path);
    await rmdir(directory);
  }
});

test("configuration preservation snapshots hash user settings and init scripts without exposing credentials", async () => {
  const directory = await mkdtemp(join(tmpdir(), "gvy-config-test-"));
  try {
    await mkdir(join(directory, "init.d"));
    await mkdir(join(directory, ".m2"));
    await writeFile(
      join(directory, "gradle.properties"),
      "password=private-fixture-secret",
    );
    await writeFile(
      join(directory, "init.d/custom.gradle"),
      "repositories { mavenLocal() }",
    );
    await writeFile(
      join(directory, ".m2/settings.xml"),
      "<settings>private-fixture-secret</settings>",
    );
    const before = await configurationSnapshot(
      directory,
      join(directory, ".m2"),
    );
    assert.equal(before.filter((item) => item.sha256).length, 3);
    assert.equal(
      JSON.stringify(before).includes("private-fixture-secret"),
      false,
    );
    await writeFile(join(directory, "init.d/custom.gradle"), "changed");
    assert.notDeepEqual(
      await configurationSnapshot(directory, join(directory, ".m2")),
      before,
    );
  } finally {
    await rm(directory, { recursive: true });
  }
});
