import { expect } from 'chai';
import * as sinon from 'sinon';
import { VersionCache } from '../../../../src/features/update/VersionCache';
import { createMementoStub, sampleRelease } from './testUtils';

describe('VersionCache', () => {
    let clock: sinon.SinonFakeTimers;

    beforeEach(() => {
        clock = sinon.useFakeTimers({ now: new Date('2025-01-01T00:00:00Z') });
    });

    afterEach(() => {
        clock.restore();
    });

    it('returns null when cache is empty', () => {
        const cache = new VersionCache(createMementoStub(), 24);
        expect(cache.getCachedRelease()).to.equal(null);
    });

    it('stores release with checkedAt/expiresAt and returns it while valid', async () => {
        const globalState = createMementoStub();
        const cache = new VersionCache(globalState, 24);

        const release = sampleRelease('2.0.0');
        await cache.setCachedRelease(release);

        const cached = cache.getCachedRelease();
        expect(cached).to.not.equal(null);
        expect(cached?.release).to.deep.equal(release);
        expect(cached?.checkedAt).to.equal(Date.now());
        expect(cached?.expiresAt).to.be.greaterThan(Date.now());
    });

    it('returns null when cache has expired', async () => {
        const globalState = createMementoStub();
        const cache = new VersionCache(globalState, 1);

        await cache.setCachedRelease(sampleRelease('2.0.0'));
        clock.tick(60 * 60 * 1000 + 1);

        expect(cache.getCachedRelease()).to.equal(null);
    });

    it('clear removes the cached value', async () => {
        const globalState = createMementoStub();
        const cache = new VersionCache(globalState, 24);

        await cache.setCachedRelease(sampleRelease('2.0.0'));
        expect(cache.getCachedRelease()).to.not.equal(null);

        await cache.clear();
        expect(cache.getCachedRelease()).to.equal(null);
    });
});
