/**
 * Computes cache key parts for Groovy LSP artifacts.
 * Outputs two lines to stdout:
 *   tag=<release tag or unknown>
 *   hash=<sha256 of prepare-server.js or missing>
 */

const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const { PINNED_RELEASE_TAG } = require("./prepare-server");

function main() {
  const prepareServerPath = path.join(__dirname, "prepare-server.js");

  // Hash the prepare-server script (cache bust when logic changes)
  let hash = "missing";
  try {
    const data = fs.readFileSync(prepareServerPath);
    hash = crypto.createHash("sha256").update(data).digest("hex");
  } catch (error) {
    console.error(`Unable to hash ${prepareServerPath}: ${error.message}`);
  }

  const tag = PINNED_RELEASE_TAG || "unknown";

  // Emit outputs for GitHub Actions
  process.stdout.write(`tag=${tag}\n`);
  process.stdout.write(`hash=${hash}\n`);
}

if (require.main === module) {
  try {
    main();
  } catch (error) {
    console.error(`cache-key computation failed: ${error.message}`);
    process.stdout.write("tag=unknown\nhash=missing\n");
    process.exit(0); // do not fail workflow; fallback values suffice
  }
}
