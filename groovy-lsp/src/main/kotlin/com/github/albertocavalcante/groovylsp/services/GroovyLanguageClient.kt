package com.github.albertocavalcante.groovylsp.services

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.services.LanguageClient

/**
 * Extended language client interface with Groovy-specific notifications.
 *
 * This interface extends the standard LSP [LanguageClient] with custom
 * notifications like `groovy/status` for server status reporting.
 *
 * @see <a href="https://github.com/eclipse-lsp4j/lsp4j#extending-the-protocol">LSP4J Extending Protocol</a>
 */
interface GroovyLanguageClient : LanguageClient {
    /**
     * Receives server status notifications.
     *
     * Based on rust-analyzer's `experimental/serverStatus` notification pattern.
     * The notification includes:
     * - `health`: Server functional state (ok, warning, error)
     * - `quiescent`: Whether there is pending background work
     * - `message`: Optional human-readable message
     * - `filesIndexed`: Current indexing progress (optional)
     * - `filesTotal`: Total files to index (optional)
     *
     * @param status The status notification payload
     */
    @JsonNotification("groovy/status")
    fun groovyStatus(status: StatusNotification)
}
