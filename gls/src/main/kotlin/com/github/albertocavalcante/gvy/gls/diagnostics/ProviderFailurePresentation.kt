// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.diagnostics

import com.github.albertocavalcante.gvy.gls.services.DiagnosticProviderFailure
import com.github.albertocavalcante.gvy.gls.services.ProviderAnalysisFailure
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticRelatedInformation
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

private const val DETAIL_LIMIT = 200
private const val PROVIDER_LIMIT = 80
private const val MESSAGE_LIMIT = 450
private const val PROVIDER_FAILURE_CODE = "gvy.providerFailure"

/** One bounded Problems notice per failed provider; original failures remain in the terminal result. */
internal fun consolidateProviderFailures(
    diagnostics: List<Diagnostic>,
    failures: List<DiagnosticProviderFailure>,
): List<Diagnostic> {
    val notices = diagnostics.filter { it.failureProvider() != null }.groupBy { it.failureProvider()!! }
    val errors = failures.groupBy { it.providerId }
    val providerIds = (notices.keys + errors.keys).distinct()
    val ordinary = diagnostics.filter { it.failureProvider() == null }
    return ordinary + providerIds.map { provider ->
        val related = notices[provider].orEmpty()
        val first = related.firstOrNull()
        val execution = errors[provider]?.firstOrNull()
        val base = first?.message?.oneLine(DETAIL_LIMIT)
        val failure = execution?.let(::providerFailureMessage)
        val message = listOfNotNull(base, failure).joinToString(" ").take(MESSAGE_LIMIT)
        Diagnostic(
            first?.range ?: Range(Position(0, 0), Position(0, 0)),
            message,
            DiagnosticSeverity.Information,
            "gvy",
            PROVIDER_FAILURE_CODE,
        ).apply {
            data = first?.data ?: mapOf("provider" to provider)
            relatedInformation = related.flatMap { it.relatedInformation.orEmpty() }
                .distinctBy { it.location.uri to it.location.range }
                .map { DiagnosticRelatedInformation(it.location, it.message.oneLine(DETAIL_LIMIT)) }
        }
    }
}

/** Status and Problems are built from the same already-filtered notices. */
internal fun providerFailureStatus(diagnostics: List<Diagnostic>): List<ProviderAnalysisFailure> =
    diagnostics.mapNotNull { diagnostic ->
        val provider = diagnostic.failureProvider() ?: return@mapNotNull null
        val data = diagnostic.data as? Map<*, *>
        val uri = data?.get("configurationUri") as? String
        val location = diagnostic.relatedInformation?.firstOrNull()?.location
        ProviderAnalysisFailure(
            provider,
            diagnostic.message,
            uri,
            location?.range ?: diagnostic.range.takeIf { uri != null },
        )
    }

private fun Diagnostic.failureProvider(): String? {
    val diagnosticCode = code ?: return null
    if (!diagnosticCode.isLeft || diagnosticCode.left != PROVIDER_FAILURE_CODE) return null
    return (data as? Map<*, *>)?.get("provider") as? String
}

private fun providerFailureMessage(failure: DiagnosticProviderFailure): String {
    val provider = failure.providerId.oneLine(PROVIDER_LIMIT)
    val detail = failure.message?.oneLine(DETAIL_LIMIT)?.takeIf { it.isNotBlank() } ?: "unknown error"
    return "$provider analysis failed: $detail"
}

private fun String.oneLine(limit: Int): String = lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    .trim().take(limit)
