import { ReleaseProvider } from './ReleaseProvider';
import { VersionChecker, ReleaseInfo, GitHubReleaseResponse } from './VersionChecker';

/**
 * Production implementation of ReleaseProvider that fetches from GitHub API.
 * Includes timeout handling to prevent hanging on slow networks.
 */
export class GitHubReleaseProvider implements ReleaseProvider {
    private static readonly RELEASES_URL =
        'https://api.github.com/repos/GroovyLanguageServer/groovy-lsp/releases/latest';
    private static readonly TIMEOUT_MS = 10_000;

    private readonly versionChecker: VersionChecker;

    constructor(versionChecker: VersionChecker = new VersionChecker()) {
        this.versionChecker = versionChecker;
    }

    async fetchLatestRelease(): Promise<ReleaseInfo | null> {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), GitHubReleaseProvider.TIMEOUT_MS);

        try {
            const response = await fetch(GitHubReleaseProvider.RELEASES_URL, {
                signal: controller.signal,
                headers: {
                    Accept: 'application/vnd.github.v3+json',
                    'User-Agent': 'gvy-ide-extension'
                }
            });

            if (!response.ok) {
                console.warn(`GitHub API returned ${response.status}: ${response.statusText}`);
                return null;
            }

            const data = (await response.json()) as GitHubReleaseResponse;
            return this.versionChecker.buildReleaseInfo(data);
        } catch (error) {
            if (error instanceof Error && error.name === 'AbortError') {
                console.warn('GitHub API request timed out');
            } else {
                console.error('Failed to fetch latest release:', error);
            }
            return null;
        } finally {
            clearTimeout(timeoutId);
        }
    }
}
