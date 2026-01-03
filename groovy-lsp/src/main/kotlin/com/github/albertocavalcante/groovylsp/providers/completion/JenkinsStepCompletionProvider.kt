package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovyjenkins.metadata.MergedGlobalVariable
import com.github.albertocavalcante.groovyjenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.MergedStepMetadata
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Provides Jenkins step completions backed by merged metadata (bundled + enrichment).
 */
object JenkinsStepCompletionProvider {
    /**
     * Get metadata for a specific Jenkins step by name.
     * Used by hover provider to show step documentation.
     */
    fun getStepMetadata(stepName: String, metadata: MergedJenkinsMetadata): MergedStepMetadata? =
        metadata.getStep(stepName)

    /**
     * Get metadata for a specific Jenkins global variable by name.
     * Used by hover provider to show global variable documentation.
     */
    fun getGlobalVariableMetadata(variableName: String, metadata: MergedJenkinsMetadata): MergedGlobalVariable? =
        metadata.getGlobalVariable(variableName)

    fun getStepCompletions(metadata: MergedJenkinsMetadata): List<CompletionItem> = metadata.steps.values.map { step ->
        // Prefer enriched description, fall back to extracted documentation
        val documentationText = step.documentation ?: "Jenkins pipeline step"
        CompletionItem().apply {
            label = step.name
            kind = CompletionItemKind.Function
            detail = step.plugin?.let { "Jenkins step ($it)" } ?: "Jenkins step"
            documentation = Either.forRight(
                MarkupContent(MarkupKind.MARKDOWN, documentationText),
            )
        }
    }

    fun getGlobalVariableCompletions(metadata: MergedJenkinsMetadata): List<CompletionItem> =
        metadata.globalVariables.values.map { globalVar ->
            // Prefer enriched description, fall back to extracted documentation
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

    fun getDeclarativeOptionCompletions(metadata: MergedJenkinsMetadata): List<CompletionItem> =
        metadata.declarativeOptions.values.map { option ->
            val documentationText = option.documentation ?: "Jenkins declarative option"
            CompletionItem().apply {
                label = option.name
                kind = CompletionItemKind.Function
                detail = "Jenkins option (${option.plugin})"
                documentation = Either.forRight(
                    MarkupContent(MarkupKind.MARKDOWN, documentationText),
                )
            }
        }

    fun getParameterCompletions(
        stepName: String,
        existingKeys: Set<String>,
        metadata: MergedJenkinsMetadata,
        useCommandExpression: Boolean = false,
    ): List<CompletionItem> {
        val params = metadata.getStep(stepName)?.namedParams
            ?: metadata.getDeclarativeOption(stepName)?.parameters
            ?: return emptyList()

        // Use named parameters for steps and declarative options.
        return params
            .filterKeys { key -> key !in existingKeys }
            .map { (key, param) ->
                CompletionItem().apply {
                    label = "$key:"
                    kind = CompletionItemKind.Property
                    detail = param.type
                    // Use SnippetBuilder for type-aware insertion
                    insertText =
                        SnippetBuilder.buildParameterSnippet(key, param, if (useCommandExpression) ", " else "")
                    insertTextFormat = InsertTextFormat.Snippet
                    // Sort required parameters first: "0_key" vs "1_key"
                    sortText = if (param.required) "0_$key" else "1_$key"
                    // Use enriched description if available
                    documentation = param.description?.let {
                        Either.forRight(MarkupContent(MarkupKind.MARKDOWN, it))
                    }
                }
            }
    }
}
