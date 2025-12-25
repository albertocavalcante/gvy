/**
 * ZIP extraction utilities for handling GitHub Actions artifacts
 * and other ZIP-based downloads.
 *
 * Uses Node.js built-in modules (zlib, stream) combined with manual
 * ZIP parsing to avoid external dependencies.
 */

const fs = require("node:fs");
const path = require("node:path");
const zlib = require("node:zlib");

// ZIP format constants
const LOCAL_FILE_HEADER_SIG = 0x04034b50;
const CENTRAL_DIR_HEADER_SIG = 0x02014b50;
const END_OF_CENTRAL_DIR_SIG = 0x06054b50;

/**
 * Checks if a file is a ZIP archive by reading magic bytes.
 * @param {string} filePath - Path to check
 * @returns {boolean}
 */
function isZipFile(filePath) {
  try {
    const fd = fs.openSync(filePath, "r");
    try {
      const buffer = Buffer.alloc(4);
      fs.readSync(fd, buffer, 0, 4, 0);
      return buffer.readUInt32LE(0) === LOCAL_FILE_HEADER_SIG;
    } finally {
      fs.closeSync(fd);
    }
  } catch {
    return false;
  }
}

/**
 * Finds the End of Central Directory record in a ZIP file.
 * Searches backwards from the end of the file.
 * @param {Buffer} buffer - File contents
 * @returns {number} Offset of EOCD signature, or -1 if not found
 */
function findEndOfCentralDirectory(buffer) {
  // EOCD is at least 22 bytes, search backwards
  const minEocdSize = 22;
  const maxCommentSize = 65535;
  const searchStart = Math.max(0, buffer.length - minEocdSize - maxCommentSize);

  for (let i = buffer.length - minEocdSize; i >= searchStart; i--) {
    if (buffer.readUInt32LE(i) === END_OF_CENTRAL_DIR_SIG) {
      return i;
    }
  }
  return -1;
}

/**
 * Parses the Central Directory to find all entries.
 * @param {Buffer} buffer - Full ZIP file contents
 * @param {number} cdOffset - Offset where Central Directory starts
 * @param {number} cdCount - Number of entries in Central Directory
 * @returns {Array<{name: string, compressedSize: number, uncompressedSize: number, compressionMethod: number, localHeaderOffset: number}>}
 */
function parseCentralDirectory(buffer, cdOffset, cdCount) {
  const entries = [];
  let offset = cdOffset;

  for (let i = 0; i < cdCount; i++) {
    if (buffer.readUInt32LE(offset) !== CENTRAL_DIR_HEADER_SIG) {
      throw new Error(`Invalid central directory header at offset ${offset}`);
    }

    const compressionMethod = buffer.readUInt16LE(offset + 10);
    const compressedSize = buffer.readUInt32LE(offset + 20);
    const uncompressedSize = buffer.readUInt32LE(offset + 24);
    const fileNameLength = buffer.readUInt16LE(offset + 28);
    const extraFieldLength = buffer.readUInt16LE(offset + 30);
    const commentLength = buffer.readUInt16LE(offset + 32);
    const localHeaderOffset = buffer.readUInt32LE(offset + 42);

    const fileName = buffer
      .subarray(offset + 46, offset + 46 + fileNameLength)
      .toString("utf8");

    entries.push({
      name: fileName,
      compressedSize,
      uncompressedSize,
      compressionMethod,
      localHeaderOffset,
    });

    offset += 46 + fileNameLength + extraFieldLength + commentLength;
  }

  return entries;
}

/**
 * Extracts file data from a local file header.
 * @param {Buffer} buffer - Full ZIP file contents
 * @param {object} entry - Entry from central directory
 * @returns {Buffer} Decompressed file contents
 */
function extractEntry(buffer, entry) {
  const offset = entry.localHeaderOffset;

  if (buffer.readUInt32LE(offset) !== LOCAL_FILE_HEADER_SIG) {
    throw new Error(`Invalid local file header at offset ${offset}`);
  }

  const fileNameLength = buffer.readUInt16LE(offset + 26);
  const extraFieldLength = buffer.readUInt16LE(offset + 28);
  const dataStart = offset + 30 + fileNameLength + extraFieldLength;
  const compressedData = buffer.subarray(
    dataStart,
    dataStart + entry.compressedSize,
  );

  // Compression method: 0 = stored, 8 = deflate
  if (entry.compressionMethod === 0) {
    return compressedData;
  } else if (entry.compressionMethod === 8) {
    // Use raw inflate (no zlib header)
    return zlib.inflateRawSync(compressedData);
  } else {
    throw new Error(
      `Unsupported compression method: ${entry.compressionMethod}`,
    );
  }
}

/**
 * Lists all entries in a ZIP file.
 * @param {string} zipPath - Path to ZIP file
 * @returns {Array<{name: string, compressedSize: number, uncompressedSize: number}>}
 */
function listZipEntries(zipPath) {
  const buffer = fs.readFileSync(zipPath);

  const eocdOffset = findEndOfCentralDirectory(buffer);
  if (eocdOffset < 0) {
    throw new Error("Invalid ZIP file: End of Central Directory not found");
  }

  const cdCount = buffer.readUInt16LE(eocdOffset + 10);
  const cdOffset = buffer.readUInt32LE(eocdOffset + 16);

  return parseCentralDirectory(buffer, cdOffset, cdCount);
}

/**
 * Extracts a JAR file from a ZIP archive.
 * Finds the first .jar file and extracts it to the destination.
 *
 * @param {string} zipPath - Path to the ZIP file
 * @param {string} destPath - Destination path for the extracted JAR
 * @returns {string} Name of the extracted JAR file
 */
function extractJarFromZip(zipPath, destPath) {
  const buffer = fs.readFileSync(zipPath);

  const eocdOffset = findEndOfCentralDirectory(buffer);
  if (eocdOffset < 0) {
    throw new Error("Invalid ZIP file: End of Central Directory not found");
  }

  const cdCount = buffer.readUInt16LE(eocdOffset + 10);
  const cdOffset = buffer.readUInt32LE(eocdOffset + 16);

  const entries = parseCentralDirectory(buffer, cdOffset, cdCount);

  const isGroovyLspJarName = (name) =>
    typeof name === "string" && name.toLowerCase().includes("groovy-lsp");

  // Find the first JAR file (prefer groovy-lsp if multiple)
  let jarEntries = entries.filter(
    (e) => e.name.endsWith(".jar") && !e.name.includes("/"),
  );

  if (jarEntries.length === 0) {
    // Try nested paths
    const nestedJars = entries.filter((e) => e.name.endsWith(".jar"));
    if (nestedJars.length === 0) {
      throw new Error("No JAR file found in ZIP archive");
    }
    jarEntries = nestedJars;
  }

  // Prefer groovy-lsp JAR if multiple
  const jarEntry =
    jarEntries.find((e) => isGroovyLspJarName(e.name)) || jarEntries[0];

  console.log(`Extracting ${jarEntry.name} from ZIP...`);

  const extractedData = extractEntry(buffer, jarEntry);

  // Ensure destination directory exists
  const destDir = path.dirname(destPath);
  if (!fs.existsSync(destDir)) {
    fs.mkdirSync(destDir, { recursive: true });
  }

  fs.writeFileSync(destPath, extractedData);

  return path.basename(jarEntry.name);
}

/**
 * Extracts a JAR from ZIP if needed, otherwise just copies/moves the file.
 * Auto-detects if input is a ZIP or JAR.
 *
 * @param {string} inputPath - Path to input file (ZIP or JAR)
 * @param {string} destPath - Destination path for JAR
 * @returns {string} Name of the resulting JAR file
 */
function extractOrCopyJar(inputPath, destPath) {
  if (isZipFile(inputPath)) {
    // Check if it's actually a JAR (JARs are also ZIPs)
    // JARs typically have META-INF/MANIFEST.MF
    try {
      const entries = listZipEntries(inputPath);
      const hasManifest = entries.some(
        (e) => e.name === "META-INF/MANIFEST.MF",
      );

      if (hasManifest) {
        // It's a JAR file, just copy it
        console.log("Input appears to be a JAR file, copying directly...");
        fs.copyFileSync(inputPath, destPath);
        return path.basename(inputPath);
      }

      // It's a ZIP containing a JAR
      return extractJarFromZip(inputPath, destPath);
    } catch (error) {
      console.warn(
        `Warning: Failed to inspect ZIP for JAR manifest, attempting extraction anyway: ${error.message}`,
      );
      // Fallback: try extraction
      return extractJarFromZip(inputPath, destPath);
    }
  } else {
    // Not a ZIP, assume it's already a JAR
    fs.copyFileSync(inputPath, destPath);
    return path.basename(inputPath);
  }
}

module.exports = {
  isZipFile,
  listZipEntries,
  extractJarFromZip,
  extractOrCopyJar,
};
