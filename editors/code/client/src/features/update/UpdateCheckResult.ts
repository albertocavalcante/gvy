import { ReleaseInfo } from './VersionChecker';

/**
 * Status of an update check operation.
 */
export type UpdateCheckStatus =
	| 'up-to-date'        // Current version >= latest release
	| 'update-available'  // Latest release > current version
	| 'cache-hit'         // Returned cached result (no network call)
	| 'unknown'           // Cannot determine (invalid versions, provider returned null)
	| 'error';            // Unexpected error occurred

/**
 * Result of an update check operation.
 */
export interface UpdateCheckResult {
	/**
	 * The outcome of the update check.
	 */
	status: UpdateCheckStatus;

	/**
	 * The current version being checked against.
	 */
	currentVersion: string;

	/**
	 * The latest available release, if known.
	 */
	latestRelease: ReleaseInfo | null;

	/**
	 * Timestamp (milliseconds since epoch) when this check was performed.
	 */
	checkedAt: number;

	/**
	 * Whether the result came from cache or a fresh network call.
	 */
	source: 'cache' | 'network';

	/**
	 * Error message if status is 'error'.
	 */
	error?: string;
}
