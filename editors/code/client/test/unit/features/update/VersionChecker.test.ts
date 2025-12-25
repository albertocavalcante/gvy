import { expect } from 'chai';
import { VersionChecker } from '../../../../src/features/update/VersionChecker';

describe('VersionChecker', () => {
    const versionChecker = new VersionChecker();

    describe('compareVersions', () => {
        it('returns 0 for identical versions (with or without v prefix)', () => {
            expect(versionChecker.compareVersions('1.0.0', '1.0.0')).to.equal(0);
            expect(versionChecker.compareVersions('v2.5.3', 'v2.5.3')).to.equal(0);
            expect(versionChecker.compareVersions('v1.0.0', '1.0.0')).to.equal(0);
            expect(versionChecker.compareVersions('1.0.0', 'v1.0.0')).to.equal(0);
        });

        it('orders versions by major/minor/patch', () => {
            expect(versionChecker.compareVersions('2.0.0', '1.9.9')).to.be.greaterThan(0);
            expect(versionChecker.compareVersions('1.2.0', '1.1.9')).to.be.greaterThan(0);
            expect(versionChecker.compareVersions('1.0.2', '1.0.1')).to.be.greaterThan(0);

            expect(versionChecker.compareVersions('1.0.0', '2.0.0')).to.be.lessThan(0);
            expect(versionChecker.compareVersions('1.1.0', '1.2.0')).to.be.lessThan(0);
            expect(versionChecker.compareVersions('1.0.1', '1.0.2')).to.be.lessThan(0);
        });

        it('returns 0 if either version is invalid', () => {
            expect(versionChecker.compareVersions('local', '1.0.0')).to.equal(0);
            expect(versionChecker.compareVersions('1.0.0', 'unknown')).to.equal(0);
            expect(versionChecker.compareVersions('invalid', 'also-invalid')).to.equal(0);
        });
    });

    describe('isValidVersion', () => {
        it('accepts semver strings (with optional v prefix)', () => {
            expect(versionChecker.isValidVersion('1.0.0')).to.equal(true);
            expect(versionChecker.isValidVersion('v2.5.3')).to.equal(true);
            expect(versionChecker.isValidVersion('0.0.1')).to.equal(true);
        });

        it('rejects local/unknown/empty', () => {
            expect(versionChecker.isValidVersion('local')).to.equal(false);
            expect(versionChecker.isValidVersion('unknown')).to.equal(false);
            expect(versionChecker.isValidVersion('')).to.equal(false);
            expect(versionChecker.isValidVersion('   ')).to.equal(false);
        });

        it('rejects non-semver strings', () => {
            expect(versionChecker.isValidVersion('1.0')).to.equal(false);
            expect(versionChecker.isValidVersion('v1')).to.equal(false);
            expect(versionChecker.isValidVersion('not-a-version')).to.equal(false);
        });
    });

    describe('buildReleaseInfo', () => {
        it('returns null for missing tag_name', () => {
            expect(
                versionChecker.buildReleaseInfo({
                    tag_name: '',
                    html_url: 'https://example.invalid',
                    published_at: '2020-01-01T00:00:00Z',
                    assets: []
                })
            ).to.equal(null);
        });

        it('returns null for missing html_url', () => {
            expect(
                versionChecker.buildReleaseInfo({
                    tag_name: 'v1.2.3',
                    html_url: '',
                    published_at: '2020-01-01T00:00:00Z',
                    assets: [{ name: 'any.jar', browser_download_url: 'https://example.invalid/any.jar' }]
                })
            ).to.equal(null);
        });

        it('returns null for missing published_at', () => {
            expect(
                versionChecker.buildReleaseInfo({
                    tag_name: 'v1.2.3',
                    html_url: 'https://github.com/x/y/releases/tag/v1.2.3',
                    published_at: '',
                    assets: [{ name: 'any.jar', browser_download_url: 'https://example.invalid/any.jar' }]
                })
            ).to.equal(null);
        });

        it('returns null when no jar asset exists', () => {
            expect(
                versionChecker.buildReleaseInfo({
                    tag_name: 'v1.2.3',
                    html_url: 'https://github.com/x/y/releases/tag/v1.2.3',
                    published_at: '2020-01-01T00:00:00Z',
                    assets: [{ name: 'checksums.txt', browser_download_url: 'https://example.invalid/checksums.txt' }]
                })
            ).to.equal(null);
        });

        it('selects a linux-amd64 jar when available', () => {
            const info = versionChecker.buildReleaseInfo({
                tag_name: 'v1.2.3',
                html_url: 'https://github.com/x/y/releases/tag/v1.2.3',
                published_at: '2020-01-01T00:00:00Z',
                assets: [
                    { name: 'checksums.txt', browser_download_url: 'https://example.invalid/checksums.txt' },
                    { name: 'groovy-lsp-1.2.3-windows.jar', browser_download_url: 'https://example.invalid/win.jar' },
                    { name: 'groovy-lsp-1.2.3-linux-amd64.jar', browser_download_url: 'https://example.invalid/linux.jar' }
                ]
            });

            expect(info).to.not.equal(null);
            expect(info?.tagName).to.equal('v1.2.3');
            expect(info?.version).to.equal('1.2.3');
            expect(info?.releaseUrl).to.equal('https://github.com/x/y/releases/tag/v1.2.3');
            expect(info?.publishedAt).to.equal('2020-01-01T00:00:00Z');
            expect(info?.downloadUrl).to.equal('https://example.invalid/linux.jar');
        });

        it('falls back to the first jar if no linux-amd64 jar exists', () => {
            const info = versionChecker.buildReleaseInfo({
                tag_name: 'v1.2.3',
                html_url: 'https://github.com/x/y/releases/tag/v1.2.3',
                published_at: '2020-01-01T00:00:00Z',
                assets: [
                    { name: 'groovy-lsp-1.2.3.jar', browser_download_url: 'https://example.invalid/main.jar' },
                    { name: 'checksums.txt', browser_download_url: 'https://example.invalid/checksums.txt' }
                ]
            });

            expect(info).to.not.equal(null);
            expect(info?.tagName).to.equal('v1.2.3');
            expect(info?.version).to.equal('1.2.3');
            expect(info?.releaseUrl).to.equal('https://github.com/x/y/releases/tag/v1.2.3');
            expect(info?.publishedAt).to.equal('2020-01-01T00:00:00Z');
            expect(info?.downloadUrl).to.equal('https://example.invalid/main.jar');
        });
    });
});
