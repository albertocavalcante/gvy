/**
 * VersionChecker handles semantic version parsing/comparison plus GitHub release response shaping.
 *
 * Note: Network fetching is intentionally not part of this module; callers should inject release JSON.
 */

export interface GitHubAsset {
  name: string;
  browser_download_url: string;
}

export interface GitHubReleaseResponse {
  tag_name: string;
  html_url: string;
  published_at: string;
  assets: GitHubAsset[];
}

export interface ReleaseInfo {
  tagName: string;
  version: string;
  releaseUrl: string;
  downloadUrl: string;
  publishedAt: string;
}

interface ParsedVersion {
  core: [string, string, string];
  prerelease: string[];
}

export class VersionChecker {
  private static readonly PREFERRED_JAR_PLATFORM = "linux-amd64";

  compareVersions(a: string, b: string): number {
    const aParts = this.parseVersion(a);
    const bParts = this.parseVersion(b);

    if (!aParts || !bParts) {
      return 0;
    }

    for (let index = 0; index < 3; index += 1) {
      const comparison = this.compareIdentifier(
        aParts.core[index],
        bParts.core[index],
      );
      if (comparison !== 0) return comparison;
    }

    const aPre = aParts.prerelease;
    const bPre = bParts.prerelease;
    if (!aPre.length || !bPre.length) {
      if (aPre.length === bPre.length) return 0;
      return aPre.length ? -1 : 1;
    }
    for (let index = 0; index < Math.min(aPre.length, bPre.length); index++) {
      const comparison = this.compareIdentifier(aPre[index], bPre[index]);
      if (comparison !== 0) return comparison;
    }
    return aPre.length === bPre.length ? 0 : aPre.length < bPre.length ? -1 : 1;
  }

  private compareIdentifier(a: string, b: string): number {
    const aNumeric = /^\d+$/.test(a);
    const bNumeric = /^\d+$/.test(b);
    if (aNumeric !== bNumeric) return aNumeric ? -1 : 1;
    // Validated numeric identifiers have no leading zeroes; length avoids Number rounding.
    if (aNumeric && a.length !== b.length) return a.length < b.length ? -1 : 1;
    // The grammar limits identifiers to ASCII, so string ordering is the SemVer ordering.
    return a === b ? 0 : a < b ? -1 : 1;
  }

  isValidVersion(version: string): boolean {
    if (!version || typeof version !== "string") {
      return false;
    }

    const normalized = version.toLowerCase().trim();
    if (["local", "unknown", ""].includes(normalized)) {
      return false;
    }

    return this.parseVersion(version) !== null;
  }

  buildReleaseInfo(releaseData: GitHubReleaseResponse): ReleaseInfo | null {
    if (
      !releaseData?.tag_name ||
      !releaseData.html_url ||
      !releaseData.published_at
    ) {
      return null;
    }

    const jarAsset = this.selectJarAsset(releaseData.assets || []);
    if (!jarAsset) {
      return null;
    }

    return {
      tagName: releaseData.tag_name,
      version: this.normalizeVersion(releaseData.tag_name),
      releaseUrl: releaseData.html_url,
      downloadUrl: jarAsset.browser_download_url,
      publishedAt: releaseData.published_at,
    };
  }

  private normalizeVersion(version: string): string {
    if (!version || typeof version !== "string") {
      return "";
    }
    return version.startsWith("v") ? version.substring(1) : version;
  }

  private parseVersion(version: string): ParsedVersion | null {
    const normalized = this.normalizeVersion(version);
    // SemVer 2.0.0 sections 2, 9 and 10; the wrapper also accepts GitHub's v prefix.
    const match =
      /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/.exec(
        normalized,
      );
    // JavaScript's $ can match before a final newline; require the entire input.
    if (!match || match[0] !== normalized) {
      return null;
    }
    const prerelease = match[4]?.split(".") ?? [];
    if (prerelease.some((identifier) => /^0\d+$/.test(identifier))) return null;
    return { core: [match[1], match[2], match[3]], prerelease };
  }

  private selectJarAsset(assets: GitHubAsset[]): GitHubAsset | null {
    if (!assets || assets.length === 0) return null;

    const preferred = assets.find(
      (asset) =>
        asset.name.endsWith(".jar") &&
        asset.name.includes(VersionChecker.PREFERRED_JAR_PLATFORM),
    );
    if (preferred) return preferred;

    return assets.find((asset) => asset.name.endsWith(".jar")) || null;
  }
}
