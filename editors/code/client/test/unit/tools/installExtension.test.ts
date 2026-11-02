import * as assert from "assert";

// eslint-disable-next-line @typescript-eslint/no-require-imports
const tool = require("../../../../tools/install-extension.js") as {
  buildEnv: (args: Record<string, unknown>) => NodeJS.ProcessEnv;
  packageCommand: (
    env: NodeJS.ProcessEnv,
    platform: NodeJS.Platform,
  ) => { cmd: string; args: string[]; shell: boolean };
  installerCommand: (
    cmd: string,
    args: string[],
    platform: NodeJS.Platform,
  ) => { cmd: string; args: string[]; shell: boolean };
  findInstaller: (
    preferred: string | null,
    platform: NodeJS.Platform,
    spawn: (
      cmd: string,
      args: string[],
      options: { shell?: boolean },
    ) => { status: number | null; error?: NodeJS.ErrnoException },
  ) => string | null;
};

describe("tools/install-extension", () => {
  describe("buildEnv", () => {
    it("passes the selection in the variables prepare-server reads", () => {
      assert.strictEqual(tool.buildEnv({ tag: "v0.4.8" }).GLS_TAG, "v0.4.8");
      assert.strictEqual(
        tool.buildEnv({ nightly: true }).GLS_CHANNEL,
        "nightly",
      );
      assert.strictEqual(
        tool.buildEnv({ url: "https://x/y.jar" }).GLS_URL,
        "https://x/y.jar",
      );
      assert.strictEqual(tool.buildEnv({ checksum: "ab" }).GLS_CHECKSUM, "ab");
      assert.ok(
        tool.buildEnv({ local: "gls.jar" }).GLS_LOCAL_JAR?.endsWith("gls.jar"),
      );
    });

    it("lets --latest override an inherited GLS_USE_PINNED", () => {
      process.env.GLS_USE_PINNED = "true";
      try {
        assert.strictEqual(
          tool.buildEnv({ latest: true }).GLS_USE_PINNED,
          undefined,
        );
      } finally {
        delete process.env.GLS_USE_PINNED;
      }
    });

    it("does not leak an inherited selection into an explicit local build", () => {
      process.env.GLS_TAG = "v0.0.1";
      try {
        assert.strictEqual(
          tool.buildEnv({ local: "gls.jar" }).GLS_TAG,
          undefined,
        );
      } finally {
        delete process.env.GLS_TAG;
      }
    });
  });

  describe("packageCommand", () => {
    it("re-runs the pnpm that launched the script", () => {
      const cmd = tool.packageCommand(
        { npm_execpath: "/pnpm/bin/pnpm.cjs" },
        "win32",
      );
      assert.deepStrictEqual(cmd, {
        cmd: process.execPath,
        args: ["/pnpm/bin/pnpm.cjs", "run", "package"],
        shell: false,
      });
    });

    it("falls back to pnpm on PATH, through cmd on Windows", () => {
      assert.deepStrictEqual(tool.packageCommand({}, "linux"), {
        cmd: "pnpm",
        args: ["run", "package"],
        shell: false,
      });
      assert.strictEqual(tool.packageCommand({}, "win32").shell, true);
    });
  });

  describe("installer lookup", () => {
    // What Node does on Windows: `code` is code.cmd, found only by cmd.exe,
    // and cmd itself always spawns: a missing command is a non-zero status
    // (9009 or 1), never ENOENT.
    const windowsSpawn =
      (missingStatus: number) =>
      (cmd: string, _args: string[], options: { shell?: boolean }) => {
        if (!options.shell) {
          return {
            status: null,
            error: Object.assign(new Error(`spawn ${cmd} EINVAL`), {
              code: "EINVAL",
            }),
          };
        }
        return { status: cmd === '"cursor"' ? 0 : missingStatus };
      };

    it("finds a .cmd launcher on Windows, skipping missing ones", () => {
      for (const missing of [9009, 1]) {
        assert.strictEqual(
          tool.findInstaller(null, "win32", windowsSpawn(missing)),
          "cursor",
          `cmd exit status ${missing} means not found`,
        );
      }
    });

    it("accepts parentheses, as in Program Files (x86)", () => {
      const exe = "C:\\Program Files (x86)\\Microsoft VS Code\\bin\\code.cmd";
      assert.strictEqual(
        tool.installerCommand(exe, [], "win32").cmd,
        `"${exe}"`,
      );
    });

    it("reports an explicitly requested installer it cannot run safely", () => {
      assert.throws(
        () =>
          tool.findInstaller("C:\\100%\\code.cmd", "win32", windowsSpawn(1)),
        /unsafe for the Windows command shell/,
      );
    });

    it("runs installers through cmd on Windows with quoted arguments", () => {
      assert.deepStrictEqual(
        tool.installerCommand(
          "code",
          ["--install-extension", "C:\\My Repo\\gvy.vsix"],
          "win32",
        ),
        {
          cmd: '"code"',
          args: ['"--install-extension"', '"C:\\My Repo\\gvy.vsix"'],
          shell: true,
        },
      );
    });

    it("refuses arguments cmd would still interpret", () => {
      assert.throws(
        () => tool.installerCommand("code", ["C:\\100%\\gvy.vsix"], "win32"),
        /unsafe for the Windows command shell/,
      );
    });

    it("spawns installers directly elsewhere", () => {
      assert.deepStrictEqual(
        tool.installerCommand(
          "code",
          ["--install-extension", "/a b.vsix"],
          "darwin",
        ),
        {
          cmd: "code",
          args: ["--install-extension", "/a b.vsix"],
          shell: false,
        },
      );
    });
  });
});
