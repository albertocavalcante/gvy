// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { readZipEntry } = require("./zip");
const pinnedManifest = require("./runtime-manifest.json");

function validateArtifact(artifact) {
  if (
    !/^[A-Za-z0-9][A-Za-z0-9._-]*\.jar$/.test(artifact.file) ||
    !/^[A-Za-z0-9._-]+$/.test(artifact.group) ||
    !/^[A-Za-z0-9._-]+$/.test(artifact.name) ||
    !/^[A-Za-z0-9._-]+$/.test(artifact.version) ||
    !/^[a-f0-9]{64}$/.test(artifact.sha256)
  ) {
    throw new Error("Invalid runtime artifact name or checksum");
  }
}

function verifyBytes(bytes, artifact) {
  const actual = crypto.createHash("sha256").update(bytes).digest("hex");
  if (actual !== artifact.sha256) {
    throw new Error(`Runtime checksum mismatch: ${artifact.file}`);
  }
}

function localArtifact(artifact, source, gradleHome) {
  if (source) {
    const candidate = path.join(source, artifact.file);
    if (fs.existsSync(candidate)) return candidate;
  } else {
    const cache = path.join(
      gradleHome,
      "caches/modules-2/files-2.1",
      artifact.group,
      artifact.name,
      artifact.version,
    );
    if (fs.existsSync(cache)) {
      for (const entry of fs.readdirSync(cache, { withFileTypes: true })) {
        if (!entry.isDirectory()) continue;
        const candidate = path.join(cache, entry.name, artifact.file);
        if (fs.existsSync(candidate)) return candidate;
      }
    }
  }
  throw new Error(
    `${artifact.file} is not available locally. Supply GVY_RUNTIME_SOURCE with the pinned artifacts, or use the existing GRADLE_USER_HOME cache. No download was attempted.`,
  );
}

function verifyRuntime(destination, manifest = pinnedManifest) {
  for (const artifact of manifest.artifacts) {
    validateArtifact(artifact);
    verifyBytes(
      fs.readFileSync(path.join(destination, artifact.file)),
      artifact,
    );
    for (const notice of ["LICENSE", "NOTICE"]) {
      if (
        fs.readFileSync(path.join(destination, `${artifact.file}.${notice}`))
          .length === 0
      ) {
        throw new Error(`Missing runtime ${notice}: ${artifact.file}`);
      }
    }
  }
  const packaged = JSON.parse(
    fs.readFileSync(path.join(destination, "manifest.json"), "utf8"),
  );
  if (JSON.stringify(packaged) !== JSON.stringify(manifest)) {
    throw new Error("Packaged runtime manifest differs from pinned metadata");
  }
}

function prepareRuntime({
  source = process.env.GVY_RUNTIME_SOURCE,
  gradleHome = process.env.GRADLE_USER_HOME ||
    path.join(os.homedir(), ".gradle"),
  destination = path.join(__dirname, "../runtime"),
  manifest = pinnedManifest,
  readEntry = readZipEntry,
} = {}) {
  // Validate the complete input before creating or replacing an artifact directory.
  const artifacts = manifest.artifacts.map((artifact) => {
    validateArtifact(artifact);
    const bytes = fs.readFileSync(localArtifact(artifact, source, gradleHome));
    verifyBytes(bytes, artifact);
    return { artifact, bytes };
  });
  if (fs.existsSync(destination)) {
    verifyRuntime(destination, manifest);
    return;
  }
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  const staging = fs.mkdtempSync(
    path.join(path.dirname(destination), ".gvy-runtime-"),
  );
  try {
    for (const { artifact, bytes } of artifacts) {
      const jar = path.join(staging, artifact.file);
      fs.writeFileSync(jar, bytes);
      for (const notice of ["LICENSE", "NOTICE"]) {
        fs.writeFileSync(
          `${jar}.${notice}`,
          readEntry(jar, `META-INF/${notice}`),
        );
      }
    }
    fs.writeFileSync(
      path.join(staging, "manifest.json"),
      `${JSON.stringify(manifest, null, 2)}\n`,
    );
    verifyRuntime(staging, manifest);
    fs.renameSync(staging, destination);
  } finally {
    // This unique staging directory is exclusively owned by this invocation.
    fs.rmSync(staging, { recursive: true, force: true });
  }
}

if (require.main === module) {
  try {
    prepareRuntime();
    console.log(
      `Verified dedicated Groovy ${pinnedManifest.version} runtime bundle`,
    );
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}

module.exports = { prepareRuntime, verifyRuntime };
