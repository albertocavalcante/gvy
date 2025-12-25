import { VersionCache } from './VersionCache';
import { ReleaseProvider } from './ReleaseProvider';
import { Clock } from './Clock';
import { UpdateCheckResult } from './UpdateCheckResult';
import { VersionChecker } from './VersionChecker';

/**
 * Orchestrates update checking by coordinating cache, version checking, and release fetching.
 *
 * Provides two check strategies:
 * - `checkForUpdate()`: Cache-first (for automatic background checks)
 * - `checkForUpdateNow()`: Force-check (for manual "Check Now" commands)
 */
export class UpdateChecker {
	private pendingAutoCheck: Promise<UpdateCheckResult> | null = null;
	private pendingManualCheck: Promise<UpdateCheckResult> | null = null;

	constructor(
		private readonly currentVersion: string,
		private readonly cache: VersionCache,
		private readonly provider: ReleaseProvider,
		private readonly clock: Clock,
		private readonly versionChecker: VersionChecker = new VersionChecker()
	) { }

	/**
	 * Checks for updates with cache-first strategy.
	 *
	 * Returns cached result if valid and not expired. Fetches from network if
	 * cache miss/expired. Clears cache if the provider returns null or throws an error.
	 *
	 * Deduplicates concurrent calls - if a check is already in progress, returns
	 * the same promise to avoid duplicate network requests.
	 *
	 * @returns Update check result
	 */
	async checkForUpdate(): Promise<UpdateCheckResult> {
		// Return existing pending check if one is in progress
		if (this.pendingAutoCheck) {
			return this.pendingAutoCheck;
		}

		// Start new check
		this.pendingAutoCheck = this.performAutoCheck();

		try {
			return await this.pendingAutoCheck;
		} finally {
			this.pendingAutoCheck = null;
		}
	}

	/**
	 * Internal implementation of auto check logic.
	 */
	private async performAutoCheck(): Promise<UpdateCheckResult> {
		// Check cache first
		const cached = this.cache.getCachedRelease();
		if (cached) {
			return {
				status: 'cache-hit',
				currentVersion: this.currentVersion,
				latestRelease: cached.release,
				checkedAt: cached.checkedAt,
				source: 'cache'
			};
		}

		// Cache miss or expired - fetch from network (clear cache on failure)
		return this.performNetworkCheck(true);
	}

	/**
	 * Internal implementation of network fetch logic.
	 * @param clearCacheOnFailure If true, clears cache on null/error (auto check behavior).
	 *                            If false, preserves cache (manual check behavior).
	 */
	private async performNetworkCheck(clearCacheOnFailure: boolean): Promise<UpdateCheckResult> {
		try {
			const latestRelease = await this.provider.fetchLatestRelease();

			if (latestRelease === null) {
				if (clearCacheOnFailure) {
					await this.cache.clear();
				}
				return {
					status: 'unknown',
					currentVersion: this.currentVersion,
					latestRelease: null,
					checkedAt: this.clock.now(),
					source: 'network'
				};
			}

			// Update cache with fresh data
			await this.cache.setCachedRelease(latestRelease);

			// Compare versions
			const status = this.determineStatus(latestRelease.version);

			return {
				status,
				currentVersion: this.currentVersion,
				latestRelease,
				checkedAt: this.clock.now(),
				source: 'network'
			};
		} catch (error) {
			if (clearCacheOnFailure) {
				await this.cache.clear();
			}
			return {
				status: 'error',
				currentVersion: this.currentVersion,
				latestRelease: null,
				checkedAt: this.clock.now(),
				source: 'network',
				error: error instanceof Error ? error.message : String(error)
			};
		}
	}

	/**
	 * Determines the update status by comparing current and latest versions.
	 */
	private determineStatus(latestVersion: string): 'up-to-date' | 'update-available' | 'unknown' {
		// Check if current version is valid
		if (!this.versionChecker.isValidVersion(this.currentVersion)) {
			return 'unknown';
		}

		// Compare versions
		const comparison = this.versionChecker.compareVersions(this.currentVersion, latestVersion);

		if (comparison < 0) {
			return 'update-available';
		}

		return 'up-to-date';
	}

	/**
	 * Checks for updates bypassing cache (force-check).
	 *
	 * Always fetches from network, bypassing cache. Preserves cache on failure
	 * (user wants to see last good data). Updates cache on successful fetch.
	 *
	 * Deduplicates concurrent calls - if a manual check is already in progress,
	 * returns the same promise to avoid duplicate network requests.
	 *
	 * @returns Update check result
	 */
	async checkForUpdateNow(): Promise<UpdateCheckResult> {
		// Return existing pending check if one is in progress
		if (this.pendingManualCheck) {
			return this.pendingManualCheck;
		}

		// Start new check
		this.pendingManualCheck = this.performManualCheck();

		try {
			return await this.pendingManualCheck;
		} finally {
			this.pendingManualCheck = null;
		}
	}

	/**
	 * Internal implementation of manual check logic.
	 * Bypasses cache, preserves cache on failure (user wants last good data).
	 */
	private async performManualCheck(): Promise<UpdateCheckResult> {
		return this.performNetworkCheck(false);
	}
}
