const { fetchJson, fetchText } = require("./http.js");

const OWNER = "albertocavalcante";
const REPO = "gvy";
const GITHUB_API_BASE = `https://api.github.com/repos/${OWNER}/${REPO}`;

const GITHUB_RELEASE_API = `${GITHUB_API_BASE}/releases/latest`;
const GITHUB_RELEASES_API = `${GITHUB_API_BASE}/releases?per_page=30`;
const GITHUB_RELEASE_TAG_API = `${GITHUB_API_BASE}/releases/tags`;

/**
 * Gets latest release info from GitHub (non-prerelease)
 */
async function getLatestReleaseInfo({ authToken } = {}) {
  return await fetchJson(GITHUB_RELEASE_API, { authToken });
}

/**
 * Gets release info for a specific tag (includes prereleases/nightlies)
 */
async function getReleaseByTag(tag, { authToken } = {}) {
  return await fetchJson(
    `${GITHUB_RELEASE_TAG_API}/${encodeURIComponent(tag)}`,
    {
      authToken,
    },
  );
}

/**
 * Gets the latest nightly (prerelease) release
 */
async function getLatestNightlyRelease({ authToken } = {}) {
  const releases = await fetchJson(GITHUB_RELEASES_API, { authToken });
  if (!Array.isArray(releases) || releases.length === 0) return null;

  const candidates = releases
    .filter((r) => !r.draft && /nightly/i.test(r.tag_name || ""))
    .sort(
      (a, b) =>
        new Date(b.published_at || b.created_at) -
        new Date(a.published_at || a.created_at),
    );

  return candidates[0] || null;
}

/**
 * Picks a JAR asset; prefer linux-amd64/universal
 */
function selectJarAsset(assets) {
  if (!assets || assets.length === 0) return null;
  const preferred = assets.find(
    (a) => a.name.endsWith(".jar") && a.name.includes("linux-amd64"),
  );
  if (preferred) return preferred;
  return assets.find((a) => a.name.endsWith(".jar")) || null;
}

/**
 * Returns the digest for assetName from sha256sum-style manifest text, or null.
 *
 * Release manifests are written as "<hash>  ./gls-0.4.8.jar" (older ones omit
 * the "./"), and sha256sum's binary mode prefixes the name with "*", so the
 * name is normalised before comparing it exactly.
 */
function checksumFromManifest(content, assetName) {
  for (const raw of content.split(/\r?\n/)) {
    const match = /^([0-9a-fA-F]{64})\s+\*?(?:\.\/)?(.+)$/.exec(raw.trim());
    if (match && match[2] === assetName) return match[1].toLowerCase();
  }
  return null;
}

/**
 * Attempts to find a checksum for the given asset name from checksums.txt
 */
async function fetchChecksumForAsset(assets, assetName, { authToken } = {}) {
  const checksumAsset = assets?.find((a) => a.name === "checksums.txt");
  if (!checksumAsset) return null;

  try {
    const content = await fetchText(checksumAsset.browser_download_url, {
      authToken,
    });
    return checksumFromManifest(content, assetName);
  } catch (error) {
    // Digests are required, so say why there is none instead of reporting
    // a generic "checksum required" later.
    throw new Error(
      `Unable to read checksums.txt for ${assetName}: ${error.message}`,
      { cause: error },
    );
  }
}

module.exports = {
  OWNER,
  REPO,
  GITHUB_API_BASE,
  GITHUB_RELEASE_API,
  GITHUB_RELEASES_API,
  GITHUB_RELEASE_TAG_API,
  getLatestReleaseInfo,
  getReleaseByTag,
  getLatestNightlyRelease,
  selectJarAsset,
  fetchChecksumForAsset,
  checksumFromManifest,
};
