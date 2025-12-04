package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovyjenkins.metadata.BundledJenkinsMetadataLoader
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.slf4j.LoggerFactory

/**
 * Provides Jenkins step completions backed by bundled metadata.
 */
object JenkinsStepCompletionProvider {
    private val logger = LoggerFactory.getLogger(JenkinsStepCompletionProvider::class.java)

    private val bundledMetadata by lazy {
        runCatching { BundledJenkinsMetadataLoader().load() }
            .onFailure { logger.warn("Failed to load bundled Jenkins metadata", it) }
            .getOrNull()
    }

    fun getBundledStepCompletions(): List<CompletionItem> {
        val metadata = bundledMetadata ?: return emptyList()

        return metadata.steps.values.map { step ->
            val documentationText = step.documentation ?: "Jenkins pipeline step"
            CompletionItem().apply {
                label = step.name
                kind = CompletionItemKind.Function
                detail = "Jenkins step (${step.plugin})"
                documentation = Either.forRight(
                    MarkupContent(MarkupKind.MARKDOWN, documentationText),
                )
            }
        }
    }
}
