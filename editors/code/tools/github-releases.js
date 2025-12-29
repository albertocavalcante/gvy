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
  return await fetchJson(`${GITHUB_RELEASE_TAG_API}/${encodeURIComponent(tag)}`, {
    authToken,
  });
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
 * Attempts to find a checksum for the given asset name from checksums.txt
 */
async function fetchChecksumForAsset(assets, assetName, { authToken } = {}) {
  const checksumAsset = assets?.find((a) => a.name === "checksums.txt");
  if (!checksumAsset) return null;

  try {
    const content = await fetchText(checksumAsset.browser_download_url, {
      authToken,
    });
    const line = content
      .split("\n")
      .find((l) => l.trim().endsWith(` ${assetName}`));
    if (!line) return null;
    const [hash] = line.trim().split(/\s+/);
    return hash || null;
  } catch (error) {
    console.warn(`Warning: Unable to read checksums.txt: ${error.message}`);
    return null;
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
};

