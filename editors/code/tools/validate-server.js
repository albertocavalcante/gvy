#!/usr/bin/env node

/**
 * Validates the Groovy Language Server JAR file
 * Used to ensure JAR is valid before packaging into VSIX
 * Exit codes:
 *   0 - JAR is valid
 *   1 - JAR is invalid or missing
 */

const fs = require("node:fs");
const path = require("node:path");
const { listZipEntries } = require("./zip.js");

const SERVER_DIR = path.join(__dirname, "..", "server");
const JAR_PATH = path.join(SERVER_DIR, "gls.jar");

/**
 * Validates that a file is a valid JAR file
 * @param {string} filePath - Path to the JAR file to validate
 * @throws {Error} if the file is not a valid JAR
 */
function validateJarFile(filePath) {
  // Check file exists
  if (!fs.existsSync(filePath)) {
    throw new Error(`JAR file not found: ${filePath}`);
  }

  // Check it's a regular file (not directory)
  const stat = fs.statSync(filePath);
  if (!stat.isFile()) {
    throw new Error(`Path is not a regular file: ${filePath}`);
  }

  // Check .jar extension
  if (!filePath.endsWith(".jar")) {
    throw new Error(`File does not have .jar extension: ${filePath}`);
  }

  // Validate ZIP structure (JAR is a ZIP file)
  // Read the first 4 bytes to check for ZIP magic number (PK\x03\x04)
  const fd = fs.openSync(filePath, "r");
  try {
    const buffer = Buffer.alloc(4);
    fs.readSync(fd, buffer, 0, 4, 0);
    const magicNumber = buffer.toString("hex");

    // ZIP files start with PK\x03\x04 (50 4B 03 04 in hex)
    if (magicNumber !== "504b0304") {
      throw new Error(
        `File is not a valid ZIP/JAR file (invalid magic number): ${filePath}`,
      );
    }

    // Check file size - should be at least 5MB for a valid fat JAR (Groovy + LSP are large)
    // Thin JARs are usually ~1.5MB or less; Shadow JARs are ~50MB+
    const MIN_SIZE_BYTES = 5 * 1024 * 1024; // 5MB
    if (stat.size < MIN_SIZE_BYTES) {
      throw new Error(
        `JAR file is too small (${(stat.size / 1024 / 1024).toFixed(
          2,
        )} MB). Expected > 5MB. This looks like a thin JAR (missing dependencies). Run 'make jar' to build the shadow JAR. Path: ${filePath}`,
      );
    }

    // Try to read the end of the file to verify ZIP end signature
    // ZIP files end with PK\x05\x06 (End of Central Directory signature)
    const endPos = Math.max(0, stat.size - 65536); // Search last 64KB
    const searchBuffer = Buffer.alloc(Math.min(65536, stat.size));
    fs.readSync(fd, searchBuffer, 0, searchBuffer.length, endPos);

    let foundEOCD = false;
    for (let i = searchBuffer.length - 22; i >= 0; i--) {
      if (
        searchBuffer[i] === 0x50 &&
        searchBuffer[i + 1] === 0x4b &&
        searchBuffer[i + 2] === 0x05 &&
        searchBuffer[i + 3] === 0x06
      ) {
        foundEOCD = true;
        break;
      }
    }

    if (!foundEOCD) {
      throw new Error(
        `JAR file appears to be truncated or corrupt (no ZIP end signature): ${filePath}`,
      );
    }

    // validate content (check for existence of key classes)
    // Using listZipEntries from zip.js (avoiding extra deps)
    try {
      const entries = listZipEntries(filePath);
      // Check for a core groovy dependency that should be in the fat jar
      // org.codehaus.groovy.ast.ClassNode is a good candidate as it comes from groovy-core
      const hasGroovy = entries.some(
        (e) => e.name === "org/codehaus/groovy/ast/ClassNode.class",
      );

      if (!hasGroovy) {
        throw new Error(
          "JAR appears to be missing dependencies (thin JAR detected). It does not contain 'org/codehaus/groovy/ast/ClassNode.class'. Please ensure you are building the shadow JAR (gradle shadowJar).",
        );
      }
    } catch (zipError) {
      // If listing fails, re-throw if it's our error, or wrap it
      if (zipError.message.includes("thin JAR")) throw zipError;
      throw new Error(`Failed to inspect JAR content: ${zipError.message}`);
    }
  } finally {
    fs.closeSync(fd);
  }
}

function main() {
  try {
    console.log(`Validating server JAR: ${JAR_PATH}`);
    validateJarFile(JAR_PATH);
    console.log("\u2713 Server JAR is valid");
    process.exit(0);
  } catch (error) {
    console.error(`\u274c Server JAR validation failed: ${error.message}`);
    console.error("");
    console.error(
      "The server JAR is missing or corrupt. This will cause the extension to fail.",
    );
    console.error("Please run: npm run prepare-server");
    process.exit(1);
  }
}

if (require.main === module) {
  main();
}

module.exports = { validateJarFile };
