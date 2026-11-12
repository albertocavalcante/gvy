// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "node:assert";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import proxyquire from "proxyquire";
import { zip } from "./zipFixture";

interface Receipt {
  success: boolean;
  stage: string;
  head: string;
  dirty: boolean;
}

describe("source install lifecycle", () => {
  let root: string;
  let build: string;
  let installs: number;
  let fail: string;
  let install: (options: { root: string; buildRoot: string }) => Receipt;
  beforeEach(() => {
    root = fs.mkdtempSync(path.join(os.tmpdir(), "gvy source lifecycle "));
    build = path.join(root, "scratch build");
    fs.mkdirSync(path.join(root, "gls"));
    fs.writeFileSync(path.join(root, "settings.gradle.kts"), "");
    fs.writeFileSync(path.join(root, "gls", "build.gradle.kts"), "");
    const extension = path.join(root, "editors", "code");
    fs.mkdirSync(extension, { recursive: true });
    fs.writeFileSync(
      path.join(extension, "package.json"),
      JSON.stringify({ version: "0.5.4", engines: { vscode: "^1.144.0" } }),
    );
    installs = 0;
    fail = "";
    const result = (stdout = "", status = 0, stderr = "") => ({
      stdout,
      status,
      stderr,
    });
    const spawn = (_cmd: string, quoted: string[]) => {
      const args = quoted.map((arg) => arg.replace(/^"(.*)"$/, "$1"));
      if (args.includes("-version"))
        return result("", 0, 'openjdk version "21.0.1"');
      if (args.includes("--version")) return result("144.144.0");
      if (args.includes("rev-parse")) return result("a".repeat(40));
      if (args.includes("--porcelain")) return result("");
      if (args.includes(":gls:stageEditorServer")) {
        if (fail === "server") return result("", 1, "compiler failure");
        const jar = path.join(
          build,
          "build",
          "gls",
          "editor-server",
          "gls.jar",
        );
        fs.mkdirSync(path.dirname(jar), { recursive: true });
        fs.writeFileSync(jar, "current jar");
      }
      if (args.includes("vsce")) {
        if (fail === "package") return result("", 1, "package failure");
        const jar = path.join(
          build,
          "build",
          "gls",
          "editor-server",
          "gls.jar",
        );
        const bundle = path.join(extension, "client", "out", "extension.js");
        fs.mkdirSync(path.dirname(bundle), { recursive: true });
        fs.writeFileSync(bundle, "current bundle");
        fs.writeFileSync(
          path.join(build, "artifacts", "gvy-source.vsix"),
          zip({
            "extension/server/gls.jar":
              fail === "verify" ? Buffer.from("stale") : fs.readFileSync(jar),
            "extension/client/out/extension.js": fs.readFileSync(bundle),
          }),
        );
      }
      if (args.includes("--install-extension")) {
        installs++;
        if (fail === "install") return result("", 1, "install failure");
      }
      if (
        args.some((arg) => arg.endsWith("smoke-server.js")) &&
        fail === "lsp-smoke"
      )
        return result("", 1, "startup failure");
      if (args.includes("version")) return result("gls version 0.5.4-SNAPSHOT");
      return result();
    };
    const tool = proxyquire("../../../../tools/source-install.js", {
      "node:child_process": { spawnSync: spawn },
      "node:fs": {
        statfsSync: () => ({ bavail: 10000, bsize: 1024 ** 2 }),
      },
      "./editor-command.js": {
        findInstaller: () => "code",
        installerCommand: (cmd: string, args: string[]) => ({
          cmd,
          args,
          shell: false,
        }),
      },
    }) as { installFromSource: typeof install };
    install = tool.installFromSource;
  });
  afterEach(() => fs.rmSync(root, { recursive: true, force: true }));

  for (const phase of ["server", "package", "verify", "lsp-smoke"]) {
    it(`does not install after a ${phase} failure and records that stage`, () => {
      fail = phase;
      assert.throws(() => install({ root, buildRoot: build }));
      assert.strictEqual(installs, 0);
      const receipt = JSON.parse(
        fs.readFileSync(
          path.join(build, "artifacts", "install-result.json"),
          "utf8",
        ),
      ) as Receipt;
      assert.strictEqual(receipt.success, false);
      assert.strictEqual(receipt.stage, phase);
      assert.strictEqual(
        fs.existsSync(
          path.join(root, "editors", "code", ".source-install.lock"),
        ),
        false,
      );
      assert.strictEqual(
        fs.existsSync(path.join(build, ".source-install.lock")),
        false,
      );
    });
  }

  it("records success only after installation returns successfully", () => {
    const receipt = install({ root, buildRoot: build });
    assert.strictEqual(installs, 1);
    assert.strictEqual(receipt.success, true);
    assert.strictEqual(receipt.stage, "installed");
    assert.strictEqual(receipt.head, "a".repeat(40));
  });

  it("reports editor installation failure without claiming success", () => {
    fail = "install";
    assert.throws(() => install({ root, buildRoot: build }));
    const receipt = JSON.parse(
      fs.readFileSync(
        path.join(build, "artifacts", "install-result.json"),
        "utf8",
      ),
    ) as Receipt;
    assert.strictEqual(installs, 1);
    assert.strictEqual(receipt.success, false);
    assert.strictEqual(receipt.stage, "install");
  });
  it("does not remove another install's guard or overwrite its receipt", () => {
    fs.mkdirSync(build, { recursive: true });
    const lock = path.join(build, ".source-install.lock");
    fs.mkdirSync(lock);
    fs.writeFileSync(path.join(lock, "owner.json"), "another installation");
    assert.throws(() => install({ root, buildRoot: build }), /already locked/);
    assert.strictEqual(installs, 0);
    assert.strictEqual(
      fs.readFileSync(path.join(lock, "owner.json"), "utf8"),
      "another installation",
    );
    assert.strictEqual(
      fs.existsSync(path.join(root, "editors", "code", ".source-install.lock")),
      false,
    );
  });
});
