// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

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
    /** Receives terminal analysis details for an advertised document-analysis capability. */
    @JsonNotification("groovy/documentAnalysis")
    fun documentAnalysis(analysis: DocumentAnalysisNotification)

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

/** A terminal analysis result for one document incarnation and version. */
data class DocumentAnalysisNotification(
    val uri: String,
    val version: Int?,
    val runId: Long,
    val sessionId: String?,
    val compiler: CompilerAnalysis,
    val providerFailures: List<ProviderAnalysisFailure>,
)

data class CompilerAnalysis(
    val complete: Boolean,
    val phase: String?,
    val reason: String?,
    val sourceMembershipKnown: Boolean? = null,
    val sourceVisibilityKnown: Boolean? = null,
    val sourceModelMessage: String? = null,
)

data class ProviderAnalysisFailure(
    val provider: String,
    val message: String,
    val configurationUri: String? = null,
    val range: org.eclipse.lsp4j.Range? = null,
)
