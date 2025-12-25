import { strict as assert } from 'assert';
import * as sinon from 'sinon';
import { VersionCache } from '../../../../src/features/update/VersionCache';
import { UpdateChecker } from '../../../../src/features/update/UpdateChecker';
import {
	createMementoStub,
	createReleaseProviderStub,
	createClockStub,
	sampleRelease
} from './testUtils';

describe('UpdateChecker', () => {
	let clock: sinon.SinonFakeTimers;

	beforeEach(() => {
		clock = sinon.useFakeTimers({ now: 1000000, shouldAdvanceTime: false });
	});

	afterEach(() => {
		clock.restore();
	});

	describe('Provider returns null', () => {
		it('should return status "unknown" when provider returns null', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const provider = createReleaseProviderStub(null);
			const clockStub = createClockStub(1000000);

			const checker = new UpdateChecker('1.0.0', cache, provider, clockStub);
			const result = await checker.checkForUpdate();

			assert.strictEqual(result.status, 'unknown');
			assert.strictEqual(result.currentVersion, '1.0.0');
			assert.strictEqual(result.latestRelease, null);
			assert.strictEqual(result.checkedAt, 1000000);
			assert.strictEqual(result.source, 'network');
		});
	});

	describe('Cache-hit path', () => {
		it('should return cached result when cache is valid', async () => {
			const release = sampleRelease('1.5.0');
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const clockStub = createClockStub(1000000);

			// Pre-populate cache (uses Date.now() from sinon fake timers)
			await cache.setCachedRelease(release);

			// Provider should NOT be called
			let providerCalled = false;
			const provider = {
				fetchLatestRelease: async () => {
					providerCalled = true;
					return release;
				}
			};

			const checker = new UpdateChecker('1.0.0', cache, provider, clockStub);
			const result = await checker.checkForUpdate();

			assert.strictEqual(providerCalled, false, 'Provider should not be called when cache is valid');
			assert.strictEqual(result.status, 'cache-hit');
			assert.strictEqual(result.currentVersion, '1.0.0');
			assert.strictEqual(result.latestRelease?.version, '1.5.0');
			assert.strictEqual(result.source, 'cache');
		});

		it('should fetch from network when cache is expired', async () => {
			const release = sampleRelease('1.5.0');
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const clockStub = createClockStub(1000000);

			// Pre-populate cache at current time
			await cache.setCachedRelease(release);

			// Advance time by 25 hours (past the 24 hour expiry)
			clock.tick(25 * 60 * 60 * 1000);

			// Provider SHOULD be called because cache is expired
			let providerCalled = false;
			const provider = {
				fetchLatestRelease: async () => {
					providerCalled = true;
					return sampleRelease('1.6.0');
				}
			};

			const checker = new UpdateChecker('1.0.0', cache, provider, clockStub);
			const result = await checker.checkForUpdate();

			assert.strictEqual(providerCalled, true, 'Provider should be called when cache is expired');
			assert.strictEqual(result.source, 'network');
			assert.strictEqual(result.latestRelease?.version, '1.6.0');
		});

		it('should fetch from network when cache is empty', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const clockStub = createClockStub(1000000);

			let providerCalled = false;
			const provider = {
				fetchLatestRelease: async () => {
					providerCalled = true;
					return sampleRelease('1.5.0');
				}
			};

			const checker = new UpdateChecker('1.0.0', cache, provider, clockStub);
			const result = await checker.checkForUpdate();

			assert.strictEqual(providerCalled, true, 'Provider should be called when cache is empty');
			assert.strictEqual(result.source, 'network');
		});
	});

	describe('Version comparison', () => {
		it('should return "update-available" when current version < latest', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const provider = createReleaseProviderStub(sampleRelease('2.0.0'));
			const clockStub = createClockStub(1000000);

			const checker = new UpdateChecker('1.5.0', cache, provider, clockStub);
			const result = await checker.checkForUpdate();

			assert.strictEqual(result.status, 'update-available');
			assert.strictEqual(result.currentVersion, '1.5.0');
			assert.strictEqual(result.latestRelease?.version, '2.0.0');
		});

		it('should return "up-to-date" when current version > latest', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const provider = createReleaseProviderStub(sampleRelease('1.0.0'));
			const clockStub = createClockStub(1000000);

			const checker = new UpdateChecker('2.0.0', cache, provider, clockStub);
			const result = await checker.checkForUpdate();

			assert.strictEqual(result.status, 'up-to-date');
			assert.strictEqual(result.currentVersion, '2.0.0');
			assert.strictEqual(result.latestRelease?.version, '1.0.0');
		});

		it('should return "up-to-date" when current version = latest', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const provider = createReleaseProviderStub(sampleRelease('1.5.0'));
			const clockStub = createClockStub(1000000);

			const checker = new UpdateChecker('1.5.0', cache, provider, clockStub);
			const result = await checker.checkForUpdate();

			assert.strictEqual(result.status, 'up-to-date');
		});

		it('should return "unknown" when current version is "local"', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const provider = createReleaseProviderStub(sampleRelease('1.5.0'));
			const clockStub = createClockStub(1000000);

			const checker = new UpdateChecker('local', cache, provider, clockStub);
			const result = await checker.checkForUpdate();

			assert.strictEqual(result.status, 'unknown');
			assert.strictEqual(result.currentVersion, 'local');
		});

		it('should return "unknown" when current version is "unknown"', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const provider = createReleaseProviderStub(sampleRelease('1.5.0'));
			const clockStub = createClockStub(1000000);

			const checker = new UpdateChecker('unknown', cache, provider, clockStub);
			const result = await checker.checkForUpdate();

			assert.strictEqual(result.status, 'unknown');
			assert.strictEqual(result.currentVersion, 'unknown');
		});

		it('should return "unknown" when current version is empty string', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const provider = createReleaseProviderStub(sampleRelease('1.5.0'));
			const clockStub = createClockStub(1000000);

			const checker = new UpdateChecker('', cache, provider, clockStub);
			const result = await checker.checkForUpdate();

			assert.strictEqual(result.status, 'unknown');
			assert.strictEqual(result.currentVersion, '');
		});
	});

	describe('Cache management', () => {
		it('should update cache when provider returns release', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const provider = createReleaseProviderStub(sampleRelease('2.0.0'));
			const clockStub = createClockStub(1000000);

			const checker = new UpdateChecker('1.0.0', cache, provider, clockStub);
			await checker.checkForUpdate();

			// Verify cache was updated
			const cached = cache.getCachedRelease();
			assert.ok(cached, 'Cache should be populated');
			assert.strictEqual(cached?.release.version, '2.0.0');
		});

		it('should clear cache when provider returns null and cache is expired', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const clockStub = createClockStub(1000000);

			// Pre-populate cache
			await cache.setCachedRelease(sampleRelease('1.5.0'));

			// Advance time to expire cache
			clock.tick(25 * 60 * 60 * 1000);

			// Provider returns null
			const provider = createReleaseProviderStub(null);

			const checker = new UpdateChecker('1.0.0', cache, provider, clockStub);
			await checker.checkForUpdate();

			// Verify cache was cleared
			assert.strictEqual(cache.getCachedRelease(), null, 'Cache should be cleared when provider returns null');
		});

		it('should not update cache when returning cached result', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const clockStub = createClockStub(1000000);

			// Pre-populate cache with initial version
			await cache.setCachedRelease(sampleRelease('1.5.0'));
			const initialCached = cache.getCachedRelease();

			// Provider would return different version, but shouldn't be called
			const provider = createReleaseProviderStub(sampleRelease('2.0.0'));

			const checker = new UpdateChecker('1.0.0', cache, provider, clockStub);
			await checker.checkForUpdate();

			// Verify cache was NOT updated (still has original)
			const finalCached = cache.getCachedRelease();
			assert.strictEqual(finalCached?.release.version, '1.5.0');
			assert.strictEqual(finalCached?.checkedAt, initialCached?.checkedAt);
		});
	});

	describe('Manual check (checkForUpdateNow)', () => {
		it('should bypass cache even when cache is valid', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const clockStub = createClockStub(1000000);

			// Pre-populate cache
			await cache.setCachedRelease(sampleRelease('1.5.0'));

			// Provider SHOULD be called even though cache is valid
			let providerCalled = false;
			const provider = {
				fetchLatestRelease: async () => {
					providerCalled = true;
					return sampleRelease('2.0.0');
				}
			};

			const checker = new UpdateChecker('1.0.0', cache, provider, clockStub);
			const result = await checker.checkForUpdateNow();

			assert.strictEqual(providerCalled, true, 'Provider should be called for manual check');
			assert.strictEqual(result.status, 'update-available');
			assert.strictEqual(result.source, 'network');
			assert.strictEqual(result.latestRelease?.version, '2.0.0');
		});

		it('should never return cache-hit status', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const clockStub = createClockStub(1000000);

			// Pre-populate cache
			await cache.setCachedRelease(sampleRelease('1.5.0'));

			const provider = createReleaseProviderStub(sampleRelease('1.5.0'));

			const checker = new UpdateChecker('1.0.0', cache, provider, clockStub);
			const result = await checker.checkForUpdateNow();

			assert.notStrictEqual(result.status, 'cache-hit');
			assert.strictEqual(result.source, 'network');
		});

		it('should update cache on successful fetch', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const clockStub = createClockStub(1000000);

			const provider = createReleaseProviderStub(sampleRelease('2.5.0'));

			const checker = new UpdateChecker('1.0.0', cache, provider, clockStub);
			await checker.checkForUpdateNow();

			// Verify cache was updated
			const cached = cache.getCachedRelease();
			assert.strictEqual(cached?.release.version, '2.5.0');
		});

		it('should preserve cache when provider returns null', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const clockStub = createClockStub(1000000);

			// Pre-populate cache
			await cache.setCachedRelease(sampleRelease('1.5.0'));

			// Provider returns null
			const provider = createReleaseProviderStub(null);

			const checker = new UpdateChecker('1.0.0', cache, provider, clockStub);
			const result = await checker.checkForUpdateNow();

			// Verify cache was PRESERVED (not cleared like in auto check)
			const cached = cache.getCachedRelease();
			assert.ok(cached, 'Cache should be preserved on manual check failure');
			assert.strictEqual(cached?.release.version, '1.5.0');
			assert.strictEqual(result.status, 'unknown');
		});
	});

	describe('Concurrent calls', () => {
		it('should deduplicate concurrent checkForUpdate calls', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const clockStub = createClockStub(1000000);

			let providerCallCount = 0;
			const provider = {
				fetchLatestRelease: async () => {
					providerCallCount++;
					return sampleRelease('2.0.0');
				}
			};

			const checker = new UpdateChecker('1.0.0', cache, provider, clockStub);

			// Call checkForUpdate three times concurrently
			const [result1, result2, result3] = await Promise.all([
				checker.checkForUpdate(),
				checker.checkForUpdate(),
				checker.checkForUpdate()
			]);

			// Should only call provider once, not three times
			assert.strictEqual(providerCallCount, 1, 'Provider should only be called once');

			// All results should be the same object (deduplication)
			assert.strictEqual(result1, result2, 'Result 1 and 2 should be the same object');
			assert.strictEqual(result2, result3, 'Result 2 and 3 should be the same object');
			assert.strictEqual(result1.latestRelease?.version, '2.0.0');
		});

		it('should deduplicate concurrent checkForUpdateNow calls', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const clockStub = createClockStub(1000000);

			let providerCallCount = 0;
			const provider = {
				fetchLatestRelease: async () => {
					providerCallCount++;
					return sampleRelease('2.0.0');
				}
			};

			const checker = new UpdateChecker('1.0.0', cache, provider, clockStub);

			// Call checkForUpdateNow three times concurrently
			const [result1, result2, result3] = await Promise.all([
				checker.checkForUpdateNow(),
				checker.checkForUpdateNow(),
				checker.checkForUpdateNow()
			]);

			// Should only call provider once, not three times
			assert.strictEqual(providerCallCount, 1, 'Provider should only be called once');

			// All results should be the same object (deduplication)
			assert.strictEqual(result1, result2, 'Result 1 and 2 should be the same object');
			assert.strictEqual(result2, result3, 'Result 2 and 3 should be the same object');
			assert.strictEqual(result1.latestRelease?.version, '2.0.0');
		});
	});

	describe('Error handling', () => {
		it('should return error status when provider throws exception (auto check)', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const clockStub = createClockStub(1000000);

			// Provider throws error
			const provider = {
				fetchLatestRelease: async () => {
					throw new Error('Network error');
				}
			};

			const checker = new UpdateChecker('1.0.0', cache, provider, clockStub);
			const result = await checker.checkForUpdate();

			assert.strictEqual(result.status, 'error');
			assert.strictEqual(result.latestRelease, null);
			assert.ok(result.error?.includes('Network error'));
		});

		it('should return error status when provider throws exception (manual check)', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const clockStub = createClockStub(1000000);

			// Provider throws error
			const provider = {
				fetchLatestRelease: async () => {
					throw new Error('API timeout');
				}
			};

			const checker = new UpdateChecker('1.0.0', cache, provider, clockStub);
			const result = await checker.checkForUpdateNow();

			assert.strictEqual(result.status, 'error');
			assert.ok(result.error?.includes('API timeout'));
		});

		it('should not poison cache when provider throws (auto check)', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const clockStub = createClockStub(1000000);

			// Pre-populate cache
			await cache.setCachedRelease(sampleRelease('1.5.0'));

			// Advance time to expire cache
			clock.tick(25 * 60 * 60 * 1000);

			// Provider throws error
			const provider = {
				fetchLatestRelease: async () => {
					throw new Error('Network error');
				}
			};

			const checker = new UpdateChecker('1.0.0', cache, provider, clockStub);
			await checker.checkForUpdate();

			// Cache should be cleared (not poisoned with error)
			assert.strictEqual(cache.getCachedRelease(), null);
		});

		it('should preserve cache when provider throws (manual check)', async () => {
			const memento = createMementoStub();
			const cache = new VersionCache(memento);
			const clockStub = createClockStub(1000000);

			// Pre-populate cache
			await cache.setCachedRelease(sampleRelease('1.5.0'));

			// Provider throws error
			const provider = {
				fetchLatestRelease: async () => {
					throw new Error('Network error');
				}
			};

			const checker = new UpdateChecker('1.0.0', cache, provider, clockStub);
			await checker.checkForUpdateNow();

			// Cache should be preserved (manual checks keep last good data)
			const cached = cache.getCachedRelease();
			assert.ok(cached);
			assert.strictEqual(cached?.release.version, '1.5.0');
		});
	});
});
