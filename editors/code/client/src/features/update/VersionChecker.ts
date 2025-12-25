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

export class VersionChecker {
    private static readonly PREFERRED_JAR_PLATFORM = 'linux-amd64';

    compareVersions(a: string, b: string): number {
        const aParts = this.parseVersion(a);
        const bParts = this.parseVersion(b);

        if (!aParts || !bParts) {
            return 0;
        }

        for (let index = 0; index < 3; index += 1) {
            if (aParts[index] > bParts[index]) return 1;
            if (aParts[index] < bParts[index]) return -1;
        }

        return 0;
    }

    isValidVersion(version: string): boolean {
        if (!version || typeof version !== 'string') {
            return false;
        }

        const normalized = version.toLowerCase().trim();
        if (['local', 'unknown', ''].includes(normalized)) {
            return false;
        }

        return this.parseVersion(version) !== null;
    }

    buildReleaseInfo(releaseData: GitHubReleaseResponse): ReleaseInfo | null {
        if (!releaseData?.tag_name || !releaseData.html_url || !releaseData.published_at) {
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
            publishedAt: releaseData.published_at
        };
    }

    private normalizeVersion(version: string): string {
        if (!version || typeof version !== 'string') {
            return '';
        }
        return version.startsWith('v') ? version.substring(1) : version;
    }

    private parseVersion(version: string): [number, number, number] | null {
        const normalized = this.normalizeVersion(version);
        const match = /^(\d+)\.(\d+)\.(\d+)/.exec(normalized);
        if (!match) {
            return null;
        }

        return [
            Number.parseInt(match[1], 10),
            Number.parseInt(match[2], 10),
            Number.parseInt(match[3], 10)
        ];
    }

    private selectJarAsset(assets: GitHubAsset[]): GitHubAsset | null {
        if (!assets || assets.length === 0) return null;

        const preferred = assets.find(
            (asset) =>
                asset.name.endsWith('.jar') && asset.name.includes(VersionChecker.PREFERRED_JAR_PLATFORM)
        );
        if (preferred) return preferred;

        return assets.find((asset) => asset.name.endsWith('.jar')) || null;
    }
}
