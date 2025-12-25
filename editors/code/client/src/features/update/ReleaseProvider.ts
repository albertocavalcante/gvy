import { ReleaseInfo } from './VersionChecker';

/**
 * Abstraction for fetching the latest release information.
 *
 * Implementations may either:
 * - Return `null` for handled error cases (network failures, invalid responses)
 * - Throw exceptions for unexpected errors
 *
 * UpdateChecker handles both gracefully with try-catch wrappers.
 */
export interface ReleaseProvider {
	/**
	 * Fetches the latest release information from the configured source.
	 *
	 * @returns The latest release info, or `null` if unavailable.
	 * @throws May throw on unexpected errors (will be caught by UpdateChecker).
	 */
	fetchLatestRelease(): Promise<ReleaseInfo | null>;
}
