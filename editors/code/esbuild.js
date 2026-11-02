const esbuild = require("esbuild");
const fs = require("node:fs");
const path = require("node:path");

const production = process.argv.includes("--production");
const watch = process.argv.includes("--watch");

/**
 * @type {import('esbuild').Plugin}
 */
const esbuildProblemMatcherPlugin = {
  name: "esbuild-problem-matcher",

  setup(build) {
    build.onStart(() => {
      console.log("[watch] build started");
    });
    build.onEnd((result) => {
      result.errors.forEach(({ text, location }) => {
        console.error(`✘ [ERROR] ${text}`);
        console.error(
          `    ${location.file}:${location.line}:${location.column}:`,
        );
      });
      console.log("[watch] build finished");
    });
  },
};

/** Copies the codicon font used by the AST webview into packaged resources. */
function copyCodicons() {
  const from = path.dirname(require.resolve("@vscode/codicons/dist/codicon.css"));
  const to = path.join(__dirname, "resources", "ast-view", "codicons");
  fs.mkdirSync(to, { recursive: true });
  for (const file of ["codicon.css", "codicon.ttf"]) {
    fs.copyFileSync(path.join(from, file), path.join(to, file));
  }
}

async function main() {
  copyCodicons();

  const ctx = await esbuild.context({
    entryPoints: ["client/src/extension.ts"],
    bundle: true,
    format: "cjs",
    minify: production,
    sourcemap: !production,
    sourcesContent: false,
    platform: "node",
    outfile: "client/out/extension.js",
    external: ["vscode"],
    logLevel: "silent",
    plugins: [
      /* add to the end of plugins array */
      esbuildProblemMatcherPlugin,
    ],
  });

  // Build webview resources
  const webviewCtx = await esbuild.context({
    entryPoints: ["client/src/features/ast/webview/main.ts"],
    bundle: true,
    format: "iife",
    minify: production,
    sourcemap: !production,
    sourcesContent: false,
    platform: "browser",
    outfile: "resources/ast-view/main.js",
    logLevel: "silent",
    plugins: [esbuildProblemMatcherPlugin],
  });

  if (watch) {
    await ctx.watch();
    await webviewCtx.watch();
  } else {
    if (production) {
      // A map left by an earlier development build would be packaged.
      fs.rmSync(path.join(__dirname, "resources", "ast-view", "main.js.map"), {
        force: true,
      });
    }
    await ctx.rebuild();
    await webviewCtx.rebuild();
    await ctx.dispose();
    await webviewCtx.dispose();
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
