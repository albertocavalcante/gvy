import * as sinon from 'sinon';
import { assert } from 'chai';
import { GitHubReleaseProvider } from '../../../../src/features/update/GitHubReleaseProvider';
import { VersionChecker } from '../../../../src/features/update/VersionChecker';

describe('GitHubReleaseProvider', () => {
    let fetchStub: sinon.SinonStub;
    let originalFetch: typeof global.fetch;

    beforeEach(() => {
        originalFetch = global.fetch;
        fetchStub = sinon.stub();
        global.fetch = fetchStub;
    });

    afterEach(() => {
        global.fetch = originalFetch;
        sinon.restore();
    });

    describe('fetchLatestRelease', () => {
        it('returns ReleaseInfo when API returns valid response', async () => {
            const mockResponse = {
                tag_name: 'v1.2.3',
                html_url: 'https://github.com/GroovyLanguageServer/groovy-lsp/releases/tag/v1.2.3',
                published_at: '2024-01-15T10:00:00Z',
                assets: [
                    { name: 'groovy-lsp-1.2.3-linux-amd64.jar', browser_download_url: 'https://example.com/download.jar' }
                ]
            };

            fetchStub.resolves({
                ok: true,
                json: async () => mockResponse
            });

            const provider = new GitHubReleaseProvider();
            const result = await provider.fetchLatestRelease();

            assert.isNotNull(result);
            assert.strictEqual(result?.version, '1.2.3');
            assert.strictEqual(result?.tagName, 'v1.2.3');
            assert.include(result?.releaseUrl, 'releases/tag');
            assert.include(result?.downloadUrl, 'download.jar');
        });

        it('returns null when API returns non-ok response', async () => {
            fetchStub.resolves({
                ok: false,
                status: 404,
                statusText: 'Not Found'
            });

            const provider = new GitHubReleaseProvider();
            const result = await provider.fetchLatestRelease();

            assert.isNull(result);
        });

        it('returns null when API response is missing required fields', async () => {
            const incompleteResponse = {
                tag_name: 'v1.2.3'
                // Missing html_url, published_at, assets
            };

            fetchStub.resolves({
                ok: true,
                json: async () => incompleteResponse
            });

            const provider = new GitHubReleaseProvider();
            const result = await provider.fetchLatestRelease();

            assert.isNull(result);
        });

        it('returns null when fetch throws network error', async () => {
            fetchStub.rejects(new Error('Network error'));

            const provider = new GitHubReleaseProvider();
            const result = await provider.fetchLatestRelease();

            assert.isNull(result);
        });

        it('returns null when fetch throws AbortError (timeout)', async () => {
            const abortError = new Error('Request timed out');
            abortError.name = 'AbortError';
            fetchStub.rejects(abortError);

            const provider = new GitHubReleaseProvider();
            const result = await provider.fetchLatestRelease();

            assert.isNull(result);
        });

        it('uses correct GitHub API URL', async () => {
            fetchStub.resolves({
                ok: false,
                status: 404,
                statusText: 'Not Found'
            });

            const provider = new GitHubReleaseProvider();
            await provider.fetchLatestRelease();

            assert.isTrue(fetchStub.calledOnce);
            const [url, options] = fetchStub.firstCall.args;
            assert.include(url, 'api.github.com');
            assert.include(url, 'GroovyLanguageServer/groovy-lsp');
            assert.include(url, 'releases/latest');
        });

        it('sends correct headers', async () => {
            fetchStub.resolves({
                ok: false,
                status: 404,
                statusText: 'Not Found'
            });

            const provider = new GitHubReleaseProvider();
            await provider.fetchLatestRelease();

            const [, options] = fetchStub.firstCall.args;
            assert.strictEqual(options.headers['Accept'], 'application/vnd.github.v3+json');
            assert.strictEqual(options.headers['User-Agent'], 'vscode-groovy-extension');
        });

        it('uses AbortController signal for timeout', async () => {
            fetchStub.resolves({
                ok: false,
                status: 404,
                statusText: 'Not Found'
            });

            const provider = new GitHubReleaseProvider();
            await provider.fetchLatestRelease();

            const [, options] = fetchStub.firstCall.args;
            assert.exists(options.signal, 'Should have AbortController signal');
        });

        it('uses custom VersionChecker when provided', async () => {
            const customChecker = new VersionChecker();
            const buildReleaseInfoSpy = sinon.spy(customChecker, 'buildReleaseInfo');

            const mockResponse = {
                tag_name: 'v2.0.0',
                html_url: 'https://example.com/release',
                published_at: '2024-01-15T10:00:00Z',
                assets: [
                    { name: 'groovy-lsp-2.0.0.jar', browser_download_url: 'https://example.com/v2.jar' }
                ]
            };

            fetchStub.resolves({
                ok: true,
                json: async () => mockResponse
            });

            const provider = new GitHubReleaseProvider(customChecker);
            await provider.fetchLatestRelease();

            assert.isTrue(buildReleaseInfoSpy.calledOnce);
        });
    });
});
