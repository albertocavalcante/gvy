import * as assert from "assert";

// eslint-disable-next-line @typescript-eslint/no-require-imports
const tool = require("../../../../tools/install-extension.js") as {
  buildEnv: (args: Record<string, unknown>) => NodeJS.ProcessEnv;
  packageCommand: (
    env: NodeJS.ProcessEnv,
    platform: NodeJS.Platform,
  ) => { cmd: string; args: string[]; shell: boolean };
};

describe("tools/install-extension", () => {
  describe("buildEnv", () => {
    it("passes the selection in the variables prepare-server reads", () => {
      assert.strictEqual(tool.buildEnv({ tag: "v0.4.8" }).GLS_TAG, "v0.4.8");
      assert.strictEqual(tool.buildEnv({ nightly: true }).GLS_CHANNEL, "nightly");
      assert.strictEqual(tool.buildEnv({ url: "https://x/y.jar" }).GLS_URL, "https://x/y.jar");
      assert.strictEqual(tool.buildEnv({ checksum: "ab" }).GLS_CHECKSUM, "ab");
      assert.ok(tool.buildEnv({ local: "gls.jar" }).GLS_LOCAL_JAR?.endsWith("gls.jar"));
    });

    it("does not leak an inherited selection into an explicit local build", () => {
      process.env.GLS_TAG = "v0.0.1";
      try {
        assert.strictEqual(tool.buildEnv({ local: "gls.jar" }).GLS_TAG, undefined);
      } finally {
        delete process.env.GLS_TAG;
      }
    });
  });

  describe("packageCommand", () => {
    it("re-runs the pnpm that launched the script", () => {
      const cmd = tool.packageCommand({ npm_execpath: "/pnpm/bin/pnpm.cjs" }, "win32");
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
});
