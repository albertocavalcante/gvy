/**
 * Server activity state - pure string union without VS Code dependency
 */
export type ServerState =
    | 'stopped'
    | 'starting'
    | 'resolving-deps'
    | 'indexing'
    | 'ready'
    | 'degraded';

/**
 * Server health status
 */
export type ServerHealth = 'ok' | 'warning' | 'error';

/**
 * Groovy server status notification parameters.
 */
export interface GroovyStatusParams {
    health: ServerHealth;
    quiescent: boolean;
    message?: string;
    filesIndexed?: number;
    filesTotal?: number;
}

/**
 * Determines the server state based on status parameters.
 */
export function determineStateFromStatus(params: GroovyStatusParams): ServerState {
    // Error health always means degraded
    if (params.health === 'error') {
        return 'degraded';
    }

    // Warning health means degraded
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
