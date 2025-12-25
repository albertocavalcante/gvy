import { expect } from 'chai';
import * as fc from 'fast-check';
import * as sinon from 'sinon';
import { VersionCache } from '../../../../src/features/update/VersionCache';
import { createMementoStub, sampleRelease } from './testUtils';

describe('VersionCache - Property Tests', () => {
    let clock: sinon.SinonFakeTimers;

    beforeEach(() => {
        clock = sinon.useFakeTimers({ now: new Date('2025-01-01T00:00:00Z') });
    });

    afterEach(() => {
        clock.restore();
    });

    const seed = 424242;

    it('stores and retrieves any release while not expired', async () => {
        await fc.assert(
            fc.asyncProperty(
                fc.integer({ min: 1, max: 48 }),
                fc.tuple(
                    fc.nat({ max: 50 }),
                    fc.nat({ max: 50 }),
                    fc.nat({ max: 50 })
                ).map(([a, b, c]) => `${a}.${b}.${c}`),
                async (checkIntervalHours, version) => {
                    const globalState = createMementoStub();
                    const cache = new VersionCache(globalState, checkIntervalHours);

                    const release = sampleRelease(version);
                    await cache.setCachedRelease(release);

                    const cached = cache.getCachedRelease();
                    expect(cached).to.not.equal(null);
                    expect(cached?.release).to.deep.equal(release);
                    expect(cached?.expiresAt).to.be.greaterThan(cached!.checkedAt);
                }
            ),
            { seed, numRuns: 50 }
        );
    });
});
