import { strict as assert } from 'assert';
import * as fc from 'fast-check';
import { VersionCache } from '../../../../src/features/update/VersionCache';
import { UpdateChecker } from '../../../../src/features/update/UpdateChecker';
import {
	createMementoStub,
	createReleaseProviderStub,
	createClockStub,
	sampleRelease
} from './testUtils';

describe('UpdateChecker (property-based)', () => {
	/**
	 * Invariant: Running checkForUpdate() twice immediately should return cache-hit on second call.
	 * This tests the idempotency of the check operation.
	 */
	it('should be idempotent: second immediate check returns cache-hit', async () => {
		await fc.assert(
			fc.asyncProperty(
				fc.integer({ min: 1, max: 100 }).map(v => `${v}.0.0`), // currentVersion
				fc.integer({ min: 1, max: 100 }).map(v => `${v}.0.0`), // latestVersion
				fc.integer({ min: 1000000, max: 9999999 }),           // timestamp
				async (currentVersion, latestVersion, timestamp) => {
					const memento = createMementoStub();
					const cache = new VersionCache(memento);
					const provider = createReleaseProviderStub(sampleRelease(latestVersion));
					const clockStub = createClockStub(timestamp);

					const checker = new UpdateChecker(currentVersion, cache, provider, clockStub);

					// First call
					const result1 = await checker.checkForUpdate();
					assert.strictEqual(result1.source, 'network');

					// Second immediate call
					const result2 = await checker.checkForUpdate();
					assert.strictEqual(result2.status, 'cache-hit');
					assert.strictEqual(result2.source, 'cache');
				}
			),
			{ seed: 424242, numRuns: 50 }
		);
	});

	/**
	 * Invariant: checkForUpdateNow() never returns cache-hit status.
	 * This ensures manual checks always bypass cache.
	 */
	it('should never return cache-hit for manual checks', async () => {
		await fc.assert(
			fc.asyncProperty(
				fc.integer({ min: 1, max: 100 }).map(v => `${v}.0.0`), // currentVersion
				fc.integer({ min: 1, max: 100 }).map(v => `${v}.0.0`), // latestVersion
				fc.integer({ min: 1000000, max: 9999999 }),           // timestamp
				async (currentVersion, latestVersion, timestamp) => {
					const memento = createMementoStub();
					const cache = new VersionCache(memento);
					const provider = createReleaseProviderStub(sampleRelease(latestVersion));
					const clockStub = createClockStub(timestamp);

					// Pre-populate cache
					await cache.setCachedRelease(sampleRelease(latestVersion));

					const checker = new UpdateChecker(currentVersion, cache, provider, clockStub);

					// Manual check
					const result = await checker.checkForUpdateNow();
					assert.notStrictEqual(result.status, 'cache-hit');
					assert.strictEqual(result.source, 'network');
				}
			),
			{ seed: 424242, numRuns: 50 }
		);
	});

	/**
	 * Invariant: Cache state matches result after update.
	 * This ensures cache coherence.
	 */
	it('should maintain cache coherence after network fetch', async () => {
		await fc.assert(
			fc.asyncProperty(
				fc.integer({ min: 1, max: 100 }).map(v => `${v}.0.0`), // currentVersion
				fc.integer({ min: 1, max: 100 }).map(v => `${v}.0.0`), // latestVersion
				fc.integer({ min: 1000000, max: 9999999 }),           // timestamp
				async (currentVersion, latestVersion, timestamp) => {
					const memento = createMementoStub();
					const cache = new VersionCache(memento);
					const provider = createReleaseProviderStub(sampleRelease(latestVersion));
					const clockStub = createClockStub(timestamp);

					const checker = new UpdateChecker(currentVersion, cache, provider, clockStub);

					// First call (network fetch)
					const result = await checker.checkForUpdate();

					if (result.source === 'network' && result.latestRelease) {
						// Verify cache was updated with the same release
						const cached = cache.getCachedRelease();
						assert.ok(cached);
						assert.strictEqual(cached?.release.version, result.latestRelease.version);
					}
				}
			),
			{ seed: 424242, numRuns: 50 }
		);
	});
});
