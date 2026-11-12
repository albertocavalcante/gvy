// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Build provenance of a server JAR, as stamped into its version.properties by
 * the Gradle build, and the record kept next to the bundled JAR
 * (server/.gls-version).
 *
 * Everything here except readJarProvenance is a pure function; the one IO
 * helper only reads a single JAR entry.
 */

const { readZipEntry } = require("./zip.js");

const SOURCES = ["local", "release", "reused"];

/**
 * Parses java.util.Properties text (the subset the build writes: key=value,
 * `#`/`!` comments, backslash escapes).
 * @param {string} text
 * @returns {Record<string, string>}
 */
function parseVersionProperties(text) {
  const result = {};
  for (const rawLine of String(text).split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#") || line.startsWith("!")) continue;
    const match = /^([^=:\s]+)\s*[=:]\s*(.*)$/.exec(line);
    if (!match) continue;
    result[match[1]] = match[2].replace(
      /\\(.)/g,
      (_, ch) => ({ n: "\n", r: "\r" })[ch] ?? ch,
    );
  }
  return result;
}

/**
 * Normalizes parsed properties to the fields the extension cares about.
 * Missing fields become "unknown" / null rather than failing: older jars
 * carry no provenance at all.
 * @param {Record<string, string>} props
 */
function provenanceFromProperties(props) {
  const text = (key) => (props[key] || "").trim();
  const dirty = text("gitDirty");
  return {
    version: text("displayVersion") || text("version") || "unknown",
    gitSha: text("gitSha") || "unknown",
    dirty: dirty === "true" ? true : dirty === "false" ? false : null,
    dirtyHash: text("gitDirtyHash"),
  };
}

/**
 * Reads a JAR's provenance, or null when it has no readable version.properties.
 * @param {string} jarPath
 */
function readJarProvenance(jarPath) {
  try {
    return provenanceFromProperties(
      parseVersionProperties(
        readZipEntry(jarPath, "version.properties").toString("utf8"),
      ),
    );
  } catch {
    return null;
  }
}

/**
 * The JSON record written to server/.gls-version.
 * `tag` keeps the legacy marker (release tag, "local", "url:<id>") that update
 * checks read; `source` says how the jar got here.
 * @param {{tag: string, source: "local"|"release"|"reused", provenance: ReturnType<typeof provenanceFromProperties>|null}} input
 */
function buildVersionRecord({ tag, source, provenance }) {
  if (!SOURCES.includes(source)) {
    throw new Error(`Unknown server source: ${source}`);
  }
  return {
    version: provenance?.version ?? "unknown",
    gitSha: provenance?.gitSha ?? "unknown",
    dirty: provenance?.dirty ?? null,
    dirtyHash: provenance?.dirtyHash ?? "",
    source,
    tag,
  };
}

/** One human-readable line describing a record, for logs. */
function describeRecord(record) {
  // Values come from inside a jar: never let them forge log lines.
  const clean = (value) => String(value).replace(/\p{Cc}/gu, "?");
  const dirty =
    record.dirty === true
      ? `dirty${record.dirtyHash ? ` ${record.dirtyHash}` : ""}`
      : record.dirty === false
        ? "clean"
        : "dirty state unknown";
  return `${clean(record.version)} (git ${clean(record.gitSha)}, ${clean(dirty)}, source=${clean(record.source)})`;
}

/**
 * Compares a local jar's provenance with the checkout's HEAD.
 *  - fresh:        built from HEAD on a clean tree
 *  - dirty:        built from HEAD, but from uncommitted changes (cannot be verified; warn only)
 *  - stale:        built from another commit, or carries no provenance
 *  - unverifiable: HEAD is unknown, so nothing can be compared
 * @param {ReturnType<typeof provenanceFromProperties>|null} provenance
 * @param {string|null} head
 * @param {boolean|null} [checkoutDirty] whether the checkout has uncommitted changes (null: unknown)
 * @returns {{state: "fresh"|"dirty"|"stale"|"unverifiable", reason: string}}
 */
function assessLocalJar(provenance, head, checkoutDirty = null) {
  if (!head) {
    return { state: "unverifiable", reason: "checkout HEAD is unknown" };
  }
  if (!provenance || provenance.gitSha === "unknown") {
    return { state: "stale", reason: "the jar carries no git provenance" };
  }
  if (provenance.gitSha !== head) {
    return {
      state: "stale",
      reason: `the jar was built from ${provenance.gitSha}, but the checkout is at ${head}`,
    };
  }
  if (provenance.dirty !== false) {
    return {
      state: "dirty",
      reason: `the jar was built from ${head} with uncommitted changes${provenance.dirtyHash ? ` (${provenance.dirtyHash})` : ""}`,
    };
  }
  if (checkoutDirty === true) {
    return {
      state: "stale",
      reason: `the jar was built clean from ${head}, but the checkout now has uncommitted changes`,
    };
  }
  return { state: "fresh", reason: "the jar matches HEAD" };
}

/**
 * Whether the environment forbids reusing an existing JAR (GLS_REQUIRE_FRESH=1|true).
 * @param {NodeJS.ProcessEnv} env
 */
function requireFresh(env) {
  return ["1", "true"].includes(
    String(env.GLS_REQUIRE_FRESH ?? "").toLowerCase(),
  );
}

module.exports = {
  parseVersionProperties,
  provenanceFromProperties,
  readJarProvenance,
  buildVersionRecord,
  describeRecord,
  assessLocalJar,
  requireFresh,
};
