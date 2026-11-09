import * as assert from "node:assert";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import proxyquire from "proxyquire";

describe("developer command boundary", () => {
  it("forwards arguments without splitting spaces or interpreting shell punctuation", () => {
    const calls: {
      cmd: string;
      args: string[];
      options: { shell: boolean };
    }[] = [];
    const dev = proxyquire("../../../../../../tools/dev-command.js", {
      "node:child_process": {
        spawnSync: (
          cmd: string,
          args: string[],
          options: { shell: boolean },
        ) => {
          calls.push({ cmd, args, options });
          return { status: 0 };
        },
      },
    }) as { command: (cmd: string, args: string[]) => void };
    // On Windows explicit shell-metacharacter arguments are rejected by the shared launcher.
    if (process.platform === "win32") {
      assert.throws(() => dev.command("tool", ["x&y"]), /unsafe/);
    } else {
      dev.command("tool", [
        "path with spaces",
        "semi;colon",
        "$(not-executed)",
      ]);
      assert.deepStrictEqual(calls[0].args, [
        "path with spaces",
        "semi;colon",
        "$(not-executed)",
      ]);
      assert.strictEqual(calls[0].options.shell, false);
    }
  });
  it("propagates a failed command instead of running later work", () => {
    const dev = proxyquire("../../../../../../tools/dev-command.js", {
      "node:child_process": { spawnSync: () => ({ status: 7 }) },
    }) as { command: (cmd: string, args: string[]) => void };
    assert.throws(() => dev.command("tool", []), /exited 7/);
  });
});

describe("pinned Just bootstrap", () => {
  const setup = proxyquire("../../../../../../tools/setup-just.js", {}) as {
    selectArtifact: (
      platform: string,
      arch: string,
    ) => { url: string; sha256: string };
    verifyArchive: (file: string, checksum: string) => void;
  };
  it("selects explicit pinned archives for all supported CI platforms", () => {
    for (const platform of ["darwin", "linux", "win32"])
      for (const arch of ["arm64", "x64"]) {
        const artifact = setup.selectArtifact(platform, arch);
        assert.ok(artifact.url.includes("/1.59.0/just-1.59.0-"));
        assert.match(artifact.sha256, /^[a-f0-9]{64}$/);
      }
    assert.throws(() => setup.selectArtifact("unknown", "x64"), /Unsupported/);
  });
  it("rejects a mismatched download before extraction and preserves an existing binary", async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "gvy-just-checksum-"));
    const binary = path.join(
      dir,
      process.platform === "win32" ? "just.exe" : "just",
    );
    fs.writeFileSync(binary, "previous verified binary");
    let extracted = false;
    const installer = proxyquire("../../../../../../tools/setup-just.js", {
      "../editors/code/tools/http.js": {
        downloadToFile: async (_url: string, file: string) =>
          fs.writeFileSync(file, "wrong bytes"),
      },
      "node:child_process": {
        execFileSync: () => {
          extracted = true;
        },
      },
    }) as { setupJust: (dir: string) => Promise<string> };
    try {
      await assert.rejects(installer.setupJust(dir), /checksum mismatch/);
      assert.strictEqual(extracted, false);
      assert.strictEqual(
        fs.readFileSync(binary, "utf8"),
        "previous verified binary",
      );
      assert.deepStrictEqual(fs.readdirSync(dir), [path.basename(binary)]);
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });
});

describe("private Just bootstrap directory", () => {
  it("does not reuse a predictable pre-created temp directory", () => {
    const temp = fs.mkdtempSync(path.join(os.tmpdir(), "gvy-bootstrap-root-"));
    const predictable = path.join(temp, "gvy-just-1.59.0");
    fs.mkdirSync(predictable);
    fs.writeFileSync(path.join(predictable, "sentinel"), "another owner");
    const installer = proxyquire("../../../../../../tools/setup-just.js", {
      "node:os": { tmpdir: () => temp },
    }) as { createDefaultBinDir: () => string };
    try {
      const selected = installer.createDefaultBinDir();
      assert.notStrictEqual(selected, predictable);
      assert.strictEqual(fs.existsSync(selected), true);
      assert.strictEqual(
        fs.readFileSync(path.join(predictable, "sentinel"), "utf8"),
        "another owner",
      );
    } finally {
      fs.rmSync(temp, { recursive: true, force: true });
    }
  });
});
