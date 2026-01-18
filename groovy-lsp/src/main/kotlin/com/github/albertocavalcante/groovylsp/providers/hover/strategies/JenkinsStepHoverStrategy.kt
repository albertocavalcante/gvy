package com.github.albertocavalcante.groovylsp.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.config.ModeResolver
import com.github.albertocavalcante.groovylsp.markdown.dsl.markdown
import com.github.albertocavalcante.groovylsp.project.JenkinsCapabilities
import com.github.albertocavalcante.groovylsp.providers.completion.JenkinsStepCompletionProvider
import com.github.albertocavalcante.groovylsp.providers.hover.HoverContext
import com.github.albertocavalcante.groovylsp.providers.hover.HoverStrategy
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Hover strategy for Jenkins Pipeline steps and global variables.
 *
 * Provides rich hover documentation for:
 * - Jenkins steps (echo, sh, readFile) with parameter info from metadata
 * - vars/ global variables from shared libraries with .txt documentation
 *
 * This strategy is only active when Jenkins mode is enabled for the file.
 */
class JenkinsStepHoverStrategy(
    private val jenkinsCapabilities: JenkinsCapabilities?,
    private val modeResolver: ModeResolver,
) : HoverStrategy {

    override fun canHandle(node: ASTNode): Boolean {
        if (node !is MethodCallExpression) return false
        return jenkinsCapabilities != null
    }

    override fun generateHover(node: ASTNode, context: HoverContext): Hover? {
        if (node !is MethodCallExpression) return null

        // Check if Jenkins mode is enabled for this file
        if (!modeResolver.isJenkinsModeEnabled(context.documentUri)) {
            return null
        }

        val stepName = node.methodAsString ?: return null

        // Try vars/ global variable hover first
        tryCreateVarsGlobalVariableHover(stepName, node)?.let { return it }

        // Then try step metadata hover
        return tryCreateStepMetadataHover(stepName, node)
    }

    /**
     * Try to create a hover for a vars/ global variable call.
     * Shows the documentation from the companion .txt file if available.
     */
    private fun tryCreateVarsGlobalVariableHover(varName: String, node: MethodCallExpression): Hover? {
        val globalVariables = jenkinsCapabilities?.getGlobalVariables() ?: return null
        val globalVar = globalVariables.find { it.name == varName } ?: return null

        // Build hover content
        val markdownContent = markdown {
            h2("Jenkins Shared Library: `$varName`")

            if (globalVar.documentation.isNotEmpty()) {
                text(globalVar.documentation)
            } else {
                text(italic("No documentation available. Add a `vars/$varName.txt` file to provide documentation."))
            }

            text("**Source:** `${globalVar.path.fileName}`")
        }

        val markupContent = MarkupContent().apply {
            kind = MarkupKind.MARKDOWN
            value = markdownContent
        }

        // LSP end is EXCLUSIVE, Groovy lastColumnNumber is 1-based INCLUSIVE
        // 1-based inclusive column N equals 0-based exclusive column N (no subtraction needed for end)
        val hoverRange = Range(
            Position(node.lineNumber - 1, node.columnNumber - 1),
            Position(node.lastLineNumber - 1, node.lastColumnNumber),
        )

        return Hover().apply {
            contents = Either.forRight(markupContent)
            range = hoverRange
        }
    }

    /**
     * Try to create a hover for a Jenkins step from bundled metadata.
     */
    private fun tryCreateStepMetadataHover(stepName: String, node: MethodCallExpression): Hover? {
        val metadata = jenkinsCapabilities?.getAllMetadata() ?: return null
        val stepMetadata = JenkinsStepCompletionProvider.getStepMetadata(stepName, metadata) ?: return null

        // Build rich hover content for Jenkins step
        val markdownContent = markdown {
            h2("Jenkins Step: `$stepName`")

            stepMetadata.documentation?.let { doc ->
                text(doc)
            }

            stepMetadata.plugin?.let { plugin ->
                text("**Plugin:** $plugin")
            }

            // Use namedParams instead of parameters for MergedStepMetadata
            if (stepMetadata.namedParams.isNotEmpty()) {
                h3("Parameters")
                list(
                    stepMetadata.namedParams.map { (name, param) ->
                        val required = if (param.required) " *(required)*" else ""
                        val defaultVal = param.defaultValue?.let { " (default: `$it`)" } ?: ""
                        val base = "**`$name`**: `${param.type}`$required$defaultVal"
                        param.description?.let { desc -> "$base\n  - $desc" } ?: base
                    },
                )
            }
        }

        val markupContent = MarkupContent().apply {
            kind = MarkupKind.MARKDOWN
            value = markdownContent
        }

        // Build hover range from the method call expression
        // LSP end is EXCLUSIVE, Groovy lastColumnNumber is 1-based INCLUSIVE
        // 1-based inclusive column N equals 0-based exclusive column N (no subtraction needed for end)
        val hoverRange = Range(
            Position(node.lineNumber - 1, node.columnNumber - 1),
            Position(node.lastLineNumber - 1, node.lastColumnNumber),
        )

        return Hover().apply {
            contents = Either.forRight(markupContent)
            range = hoverRange
        }
    }
}
