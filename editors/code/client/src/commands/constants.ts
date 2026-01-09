/**
 * Command ID constants for the Groovy Language Server extension.
 * These are kept in a separate file to avoid importing vscode at module load time,
 * which breaks unit tests that need to mock vscode.
 */

/**
 * Command ID for retrying dependency resolution.
 * Exported for use by error notification handler.
 */
export const RETRY_DEPENDENCY_RESOLUTION = "groovy.retryDependencyResolution";
