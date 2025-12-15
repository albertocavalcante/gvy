package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovyjenkins.metadata.BundledJenkinsMetadataLoader
import com.github.albertocavalcante.groovyjenkins.metadata.GlobalVariableMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.JenkinsStepMetadata
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

    /**
     * Get metadata for a specific Jenkins step by name.
     * Used by hover provider to show step documentation.
     */
    fun getStepMetadata(stepName: String): JenkinsStepMetadata? = bundledMetadata?.getStep(stepName)

    /**
     * Get metadata for a specific Jenkins global variable by name.
     * Used by hover provider to show global variable documentation.
     */
    fun getGlobalVariableMetadata(variableName: String): GlobalVariableMetadata? =
        bundledMetadata?.getGlobalVariable(variableName)

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

    fun getBundledGlobalVariableCompletions(): List<CompletionItem> {
        val metadata = bundledMetadata ?: return emptyList()

        return metadata.globalVariables.values.map { globalVar ->
            val documentationText = globalVar.documentation ?: "Jenkins global variable"
            CompletionItem().apply {
                label = globalVar.name
                kind = CompletionItemKind.Variable
                detail = "Jenkins global (${globalVar.type.substringAfterLast('.')})"
                documentation = Either.forRight(
                    MarkupContent(MarkupKind.MARKDOWN, documentationText),
                )
            }
        }
    }

    fun getBundledParameterCompletions(stepName: String, existingKeys: Set<String>): List<CompletionItem> {
        val metadata = bundledMetadata ?: return emptyList()
        val step = metadata.getStep(stepName) ?: return emptyList()

        return step.parameters
            .filterKeys { key -> key !in existingKeys }
            .map { (key, param) ->
                CompletionItem().apply {
                    label = "$key:"
                    kind = CompletionItemKind.Property
                    detail = param.type
                    documentation = param.documentation?.let {
                        Either.forRight(MarkupContent(MarkupKind.MARKDOWN, it))
                    }
                }
            }
    }
}
