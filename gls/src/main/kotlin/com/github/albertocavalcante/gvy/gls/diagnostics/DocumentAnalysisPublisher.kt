// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.diagnostics

import com.github.albertocavalcante.gvy.gls.compilation.CompilationResult
import com.github.albertocavalcante.gvy.gls.compilation.phaseName
import com.github.albertocavalcante.gvy.gls.compilation.sourceModelMessage
import com.github.albertocavalcante.gvy.gls.compilation.userMessage
import com.github.albertocavalcante.gvy.gls.services.CompilerAnalysis
import com.github.albertocavalcante.gvy.gls.services.DocumentAnalysisNotification
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import com.github.albertocavalcante.gvy.gls.services.GroovyLanguageClient
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.services.LanguageClient
import java.net.URI

internal data class DocumentAnalysisContext(
    val uri: URI,
    val snapshot: DocumentSnapshot,
    val runId: Long,
    val sessionId: String?,
)

internal class DocumentAnalysisPublisher(
    private val documents: DocumentProvider,
    private val publicationLock: Any,
    private val client: () -> LanguageClient?,
    private val supported: () -> Boolean,
) {
    fun publish(
        context: DocumentAnalysisContext,
        compilation: CompilationResult,
        diagnostics: List<Diagnostic>,
        isCurrent: () -> Boolean,
    ) {
        if (!supported()) return
        val recipient = client() as? GroovyLanguageClient ?: return
        val outcome = compilation.outcome
        val payload = DocumentAnalysisNotification(
            context.uri.toString(),
            context.snapshot.version,
            context.runId,
            context.sessionId,
            CompilerAnalysis(
                complete = outcome != null && !outcome.typesUnresolved,
                phase = outcome?.achievedPhase?.let(::phaseName),
                reason = outcome?.takeIf { it.typesUnresolved }?.userMessage(),
                sourceMembershipKnown = compilation.sourceMembershipKnown,
                sourceVisibilityKnown = compilation.sourceVisibilityKnown,
                sourceModelMessage = compilation.sourceModelMessage(),
            ),
            providerFailureStatus(diagnostics),
        )
        documents.withCurrentSnapshot(context.uri, context.snapshot) {
            synchronized(publicationLock) {
                if (isCurrent()) recipient.documentAnalysis(payload)
            }
        }
    }
}
