/**
 * Server activity state - pure string union without VS Code dependency
 */
export type ServerState =
    | 'stopped'
    | 'starting'
    | 'resolving-deps'
    | 'indexing'
    | 'ready'
    | 'degraded'
    | 'error'; // Distinct from 'degraded' - indicates fatal error requiring user action

/**
 * Server health status
 */
export type ServerHealth = 'ok' | 'warning' | 'error';

/**
 * Error type discriminators matching server-side sealed interface.
 */
export type ErrorType =
    | 'GRADLE_JDK_INCOMPATIBLE'
    | 'NO_BUILD_TOOL'
    | 'DEPENDENCY_RESOLUTION_FAILED'
    | 'JAVA_NOT_FOUND'
    | string; // Allow custom error codes via GenericError

/**
 * Base interface for all error details.
 * Matches server-side sealed interface ErrorDetails.
 */
export interface ErrorDetails {
    type: ErrorType;
    suggestions: string[];
}

/**
 * Gradle/JDK version incompatibility error.
 */
export interface GradleJdkIncompatibleError extends ErrorDetails {
    type: 'GRADLE_JDK_INCOMPATIBLE';
    gradleVersion: string;
    jdkVersion: number;
    minGradleVersion: string;
    maxJdkVersion: string | null;
}

/**
 * No build tool detected error.
 */
export interface NoBuildToolError extends ErrorDetails {
    type: 'NO_BUILD_TOOL';
    searchedPaths: string[];
}

/**
 * Dependency resolution failure error.
 */
export interface DependencyResolutionError extends ErrorDetails {
    type: 'DEPENDENCY_RESOLUTION_FAILED';
    buildTool: string;
    cause: string | null;
}

/**
 * Java runtime not found error.
 */
export interface JavaNotFoundError extends ErrorDetails {
    type: 'JAVA_NOT_FOUND';
    configuredPath: string | null;
    searchedLocations: string[];
}

/**
 * Groovy server status notification parameters.
 */
export interface GroovyStatusParams {
    health: ServerHealth;
    quiescent: boolean;
    message?: string;
    filesIndexed?: number;
    filesTotal?: number;
    errorCode?: string;
    errorDetails?: ErrorDetails;
}

/**
 * Determines the server state based on status parameters.
 *
 * State mapping:
 * - 'error': Health is 'error' OR errorCode is present (fatal, requires user action)
 * - 'degraded': Health is 'warning' (partial functionality, can continue)
 * - Other states based on quiescent and message content
 */
export function determineStateFromStatus(params: GroovyStatusParams): ServerState {
    // Error with errorCode indicates a fatal error requiring user action (e.g., JDK incompatible)
    if (params.health === 'error' || params.errorCode) {
        return 'error';
    }

    // Warning health means degraded (partial functionality, e.g., some deps missing)
    if (params.health === 'warning') {
        return 'degraded';
    }

    // Not quiescent means server is working
    if (!params.quiescent) {
        // Determine specific state from message
        const message = (params.message || '').toLowerCase();
        if (message.includes('resolving') || message.includes('dependencies')) {
            return 'resolving-deps';
        } else if (message.includes('indexing')) {
            return 'indexing';
        } else if (message.includes('initializing') || message.includes('starting')) {
            return 'starting';
        } else {
            // Default to indexing if we have file counts
            if (params.filesTotal && params.filesTotal > 0) {
                return 'indexing';
            } else {
                return 'starting';
            }
        }
    }

    // Quiescent and healthy = ready
    return 'ready';
}

/**
 * Infers state from a generic progress message (fallback logic).
 */
export function inferStateFromMessage(message: string, currentFilesTotal: number | undefined): ServerState | undefined {
    // Skip inference if we have explicit file counts (means we have better data source)
    if (currentFilesTotal !== undefined) {
        return undefined;
    }

    const lowerMsg = message.toLowerCase();

    // Check for errors FIRST
    if (lowerMsg.includes('failed') || lowerMsg.includes('error')) {
        return 'degraded';
    } else if (
        lowerMsg.includes('resolving') ||
        lowerMsg.includes('gradle') ||
        lowerMsg.includes('maven') ||
        lowerMsg.includes('dependencies') ||
        lowerMsg.includes('connecting')
    ) {
        return 'resolving-deps';
    } else if (
        lowerMsg.includes('indexing') ||
        lowerMsg.includes('compiling') ||
        lowerMsg.includes('analyzing')
    ) {
        return 'indexing';
    } else if (
        lowerMsg.includes('ready') ||
        lowerMsg.includes('complete') ||
        lowerMsg.includes('loaded')
    ) {
        return 'ready';
    }

    return undefined;
}
