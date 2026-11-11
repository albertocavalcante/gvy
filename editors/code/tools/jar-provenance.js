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

/**
 * Parses server/.gls-version: the JSON record, or the legacy plain-text tag.
 * @param {string} content
 * @returns {{tag: string} & Partial<ReturnType<typeof buildVersionRecord>> | null}
 */
function parseVersionRecord(content) {
  const trimmed = String(content).trim();
  if (!trimmed) return null;
  if (trimmed.startsWith("{")) {
    try {
      const record = JSON.parse(trimmed);
      const tag = record.tag ?? record.version;
      return typeof tag === "string" && tag ? { ...record, tag } : null;
    } catch {
      return null;
    }
  }
  return { tag: trimmed };
}

/** One human-readable line describing a record, for logs. */
function describeRecord(record) {
  const dirty =
    record.dirty === true
      ? `dirty${record.dirtyHash ? ` ${record.dirtyHash}` : ""}`
      : record.dirty === false
        ? "clean"
        : "dirty state unknown";
  return `${record.version} (git ${record.gitSha}, ${dirty}, source=${record.source})`;
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
  parseVersionRecord,
  describeRecord,
  requireFresh,
};
