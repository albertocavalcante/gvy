/* eslint-disable @typescript-eslint/no-require-imports -- CommonJS tools are checked separately. */
import * as assert from "node:assert";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { zip } from "./zipFixture";

interface Plan {
  jar: string;
  vsix: string;
  steps: { phase: string; args: string[]; env: NodeJS.ProcessEnv }[];
}
const {
  createSourcePlan,
  executeBuild,
  localJarMatches,
  sourceEnvironment,
  replaceLocalJar,
  verifyVsix,
  finishReceipt,
} = require("../../../../tools/source-install.js") as {
  createSourcePlan: (
    root: string,
    build: string,
    env: NodeJS.ProcessEnv,
  ) => Plan;
  executeBuild: (plan: Plan, run: (step: { phase: string }) => unknown) => void;
  localJarMatches: (source: string, dest: string) => boolean;
  replaceLocalJar: (
    source: string,
    dest: string,
    validate: (file: string) => void,
  ) => void;
  finishReceipt: (
    file: string,
    receipt: Record<string, unknown>,
    locks: string[],
    write: () => void,
  ) => void;
  verifyVsix: (
    vsix: string,
    jar: string,
    bundle: string,
  ) => { jar: string; bundle: string; vsix: string };
  sourceEnvironment: (env: NodeJS.ProcessEnv, jar: string) => NodeJS.ProcessEnv;
};

describe("source installation contract", () => {
  it("stages and smoke-tests this checkout before packaging its exact jar", () => {
    const plan = createSourcePlan("/repo with spaces", "/scratch build", {});
    assert.ok(
      plan.steps[0].args.some(
        (arg) => arg.replaceAll('"', "") === ":gls:stageEditorServer",
      ),
    );
    assert.ok(
      plan.steps[0].args.some(
        (arg) => arg.replaceAll('"', "") === ":gls:smokeShadowJar",
      ),
    );
    const packaging = plan.steps.at(-1)!;
    assert.strictEqual(packaging.env.GLS_LOCAL_JAR, plan.jar);
    assert.strictEqual(packaging.env.FORCE_DOWNLOAD, "true");
    assert.strictEqual(packaging.env.SKIP_PREPARE_SERVER, "false");
    assert.ok(
      packaging.args.some((arg) => arg.replaceAll('"', "") === plan.vsix),
    );
    assert.ok(
      !plan.steps.some((s: { args: string[] }) => s.args.includes("--latest")),
    );
  });

  it("removes inherited release selectors and skip toggles", () => {
    const env = sourceEnvironment(
      {
        GLS_TAG: "v0.1.0",
        GLS_CHANNEL: "release",
        GLS_URL: "https://example.test",
        SKIP_PREPARE_SERVER: "true",
        GLS_USE_PINNED: "true",
        USE_LATEST_GLS: "true",
        USE_LATEST_GROOVY_LSP: "true",
        GLS_ALLOW_PINNED_FALLBACK: "true",
      },
      "/build/current.jar",
    );
    assert.strictEqual(env.GLS_TAG, undefined);
    assert.strictEqual(env.GLS_CHANNEL, undefined);
    assert.strictEqual(env.GLS_URL, undefined);
    assert.strictEqual(env.USE_LATEST_GROOVY_LSP, undefined);
    assert.strictEqual(env.GLS_ALLOW_PINNED_FALLBACK, "false");
    assert.strictEqual(env.GLS_LOCAL_JAR, "/build/current.jar");
  });

  it("stops before packaging when the server build fails", () => {
    const plan = createSourcePlan("/repo", "/build", {});
    const phases: string[] = [];
    assert.throws(
      () =>
        executeBuild(plan, (step: { phase: string }) => {
          phases.push(step.phase);
          throw new Error("compiler failed");
        }),
      /compiler failed/,
    );
    assert.deepStrictEqual(phases, ["server"]);
  });

  it("compares local jar contents instead of modification times", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "gvy-content-"));
    try {
      const source = path.join(dir, "source.jar");
      const dest = path.join(dir, "dest.jar");
      fs.writeFileSync(source, "new jar");
      fs.writeFileSync(dest, "old jar");
      fs.utimesSync(source, new Date(0), new Date(0));
      assert.strictEqual(localJarMatches(source, dest), false);
      fs.copyFileSync(source, dest);
      assert.strictEqual(localJarMatches(source, dest), true);
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });
});

describe("source artifact verification and recovery", () => {
  let dir: string;
  beforeEach(() => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), "gvy-artifact-"));
  });
  afterEach(() => fs.rmSync(dir, { recursive: true, force: true }));

  it("preserves the previous local jar when validation fails", () => {
    const source = path.join(dir, "source.jar"),
      dest = path.join(dir, "dest.jar");
    fs.writeFileSync(source, "invalid candidate");
    fs.writeFileSync(dest, "previous good jar");
    assert.throws(
      () =>
        replaceLocalJar(source, dest, () => {
          throw new Error("invalid jar");
        }),
      /invalid jar/,
    );
    assert.strictEqual(fs.readFileSync(dest, "utf8"), "previous good jar");
    assert.deepStrictEqual(fs.readdirSync(dir).sort(), [
      "dest.jar",
      "source.jar",
    ]);
  });

  it("verifies both embedded artifacts and rejects a stale packaged jar", () => {
    const jar = path.join(dir, "source.jar"),
      bundle = path.join(dir, "extension.js"),
      vsix = path.join(dir, "source.vsix");
    fs.writeFileSync(jar, "current jar");
    fs.writeFileSync(bundle, "current client");
    const entries = {
      "extension/server/gls.jar": fs.readFileSync(jar),
      "extension/client/out/extension.js": fs.readFileSync(bundle),
    };
    fs.writeFileSync(vsix, zip(entries));
    assert.match(verifyVsix(vsix, jar, bundle).jar, /^[a-f0-9]{64}$/);
    fs.writeFileSync(
      vsix,
      zip({ ...entries, "extension/server/gls.jar": Buffer.from("stale jar") }),
    );
    assert.throws(() => verifyVsix(vsix, jar, bundle), /Packaged server/);
    fs.writeFileSync(
      vsix,
      zip({
        ...entries,
        "extension/client/out/extension.js": Buffer.from("stale client"),
      }),
    );
    assert.throws(() => verifyVsix(vsix, jar, bundle), /Packaged client/);
  });
});

describe("receipt failure recovery", () => {
  it("releases guards and reports an already completed installation accurately", () => {
    const lock = fs.mkdtempSync(path.join(os.tmpdir(), "gvy-lock-"));
    assert.throws(
      () =>
        finishReceipt(
          "unused",
          { success: true, stage: "installed" },
          [lock],
          () => {
            throw new Error("disk full");
          },
        ),
      (error: unknown) => {
        assert.strictEqual((error as { installed: boolean }).installed, true);
        return true;
      },
    );
    assert.strictEqual(fs.existsSync(lock), false);
  });
});
