// @ts-check
const path = require("node:path");
const { ESLint } = require("eslint");

async function lintTools() {
  const root = path.resolve(__dirname, "..", "..", "..");
  const eslint = new ESLint({
    cwd: root,
    overrideConfigFile: path.join(__dirname, "eslint.config.mjs"),
  });
  const results = await eslint.lintFiles([
    "editors/code/tools/*.js",
    "tools/dev-command.js",
    "tools/setup-just.js",
    "tools/test-just.js",
  ]);
  const formatter = await eslint.loadFormatter("stylish");
  const output = await formatter.format(results);
  if (output) process.stdout.write(output);
  if (results.some((result) => result.errorCount || result.warningCount))
    process.exitCode = 1;
}

if (require.main === module)
  lintTools().catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
module.exports = { lintTools };
