package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovyjenkins.metadata.BundledJenkinsMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.GlobalVariableMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.JenkinsStepMetadata
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Provides Jenkins step completions backed by bundled metadata.
 */
object JenkinsStepCompletionProvider {
    /**
     * Get metadata for a specific Jenkins step by name.
     * Used by hover provider to show step documentation.
     */
    fun getStepMetadata(stepName: String, metadata: BundledJenkinsMetadata): JenkinsStepMetadata? =
        metadata.getStep(stepName)

    /**
     * Get metadata for a specific Jenkins global variable by name.
     * Used by hover provider to show global variable documentation.
     */
    fun getGlobalVariableMetadata(variableName: String, metadata: BundledJenkinsMetadata): GlobalVariableMetadata? =
        metadata.getGlobalVariable(variableName)

    fun getStepCompletions(metadata: BundledJenkinsMetadata): List<CompletionItem> = metadata.steps.values.map { step ->
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

    fun getGlobalVariableCompletions(metadata: BundledJenkinsMetadata): List<CompletionItem> =
        metadata.globalVariables.values.map { globalVar ->
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

    fun getParameterCompletions(
        stepName: String,
        existingKeys: Set<String>,
        metadata: BundledJenkinsMetadata,
    ): List<CompletionItem> {
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
