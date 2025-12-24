package com.github.albertocavalcante.groovylsp.services

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.services.LanguageClient

/**
 * Extended language client interface with Groovy-specific notifications.
 *
 * This interface extends the standard LSP [LanguageClient] with custom
 * notifications like `groovy/status` for server readiness signaling.
 *
 * @see <a href="https://github.com/eclipse-lsp4j/lsp4j#extending-the-protocol">LSP4J Extending Protocol</a>
 */
interface GroovyLanguageClient : LanguageClient {
    /**
     * Receives server status notifications.
     *
     * This notification is sent during server lifecycle transitions:
     * - `Starting`: Server is initializing
     * - `Ready`: Server is ready to handle requests
     * - `Indexing`: Background indexing in progress
     * - `Error`: An error occurred
     *
     * @param status The status notification payload
     */
    @JsonNotification("groovy/status")
    fun groovyStatus(status: StatusNotification)
}
