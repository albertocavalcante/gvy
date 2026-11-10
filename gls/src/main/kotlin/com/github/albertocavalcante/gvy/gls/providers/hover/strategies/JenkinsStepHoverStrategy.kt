package com.github.albertocavalcante.gvy.gls.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.markdown.dsl.markdown
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.config.ModeResolver
import com.github.albertocavalcante.gvy.gls.project.JenkinsCapabilities
import com.github.albertocavalcante.gvy.gls.providers.completion.JenkinsStepCompletionProvider
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverContext
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverStrategy
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.net.URI

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

        // Declarative constructs first: inside options {} `timeout` is the option, not the step.
        tryCreateDeclarativeHover(stepName, node, context)?.let { return it }

        // Try vars/ global variable hover first
        tryCreateVarsGlobalVariableHover(stepName, node)?.let { return it }

        // Then try step metadata hover
        return tryCreateStepMetadataHover(stepName, node, context.documentUri)
    }

    /**
     * Hover for declarative options (a call directly in `options {}`) and for declarative sections and
     * directives (`pipeline`, `agent`, `stages`, `options`, `when`, ...), from the bundled enrichment.
     */
    private fun tryCreateDeclarativeHover(name: String, node: MethodCallExpression, context: HoverContext): Hover? {
        val metadata = jenkinsCapabilities?.getAllMetadata(context.documentUri) ?: return null
        val astModel = context.astModel ?: return null
        val enclosing = enclosingCallNames(node, astModel)
        // Sections and options only exist in Declarative Pipeline; a scripted `stage('x') {}` (also inside
        // `script {}` or `node {}`) is the step.
        val names = enclosing.map { it.name }
        // A closure passed as a map value (`parallel(a: { ... })`) is a scripted body, like `script {}`.
        val declarative = (name == PIPELINE || PIPELINE in names) &&
            enclosing.none { it.name in SCRIPTED_BODIES || !it.isBlockArgument }
        if (!declarative) return null
        val parent = names.firstOrNull()
        val option = metadata.declarativeOptions[name]?.takeIf { parent == OPTIONS_BLOCK }
        // A section or directive only where its schema allows it: `stage('x') {}` inside `steps {}` is the step.
        val section = metadata.sections[name]?.takeIf { isAllowedIn(it.allowedIn, parent) }
        val directive = metadata.directives[name]?.takeIf { isAllowedIn(it.allowedIn, parent) }
        val content = when {
            option != null -> markdown {
                h2("Declarative option `$name`")
                option.documentation?.let(::text)
                text("**Plugin:** ${option.plugin}")
                if (option.parameters.isNotEmpty()) {
                    h3("Parameters")
                    list(option.parameters.map { (key, param) -> "**`$key`**: `${param.type}`" })
                }
            }
            section != null -> declarativeBlockMarkdown(
                "section",
                name,
                section.description,
                section.allowedIn,
                section.documentationUrl,
            )
            directive != null -> declarativeBlockMarkdown(
                "directive",
                name,
                directive.description,
                directive.allowedIn,
                directive.documentationUrl,
            )
            else -> return null
        }
        return Hover().apply {
            contents = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, content))
            // Only the name: a section's call spans its whole block (`pipeline` would highlight the file).
            range = hoverRangeOf(node.method) ?: hoverRangeOf(node)
        }
    }

    /** `root` (only `pipeline`) means top level, outside any block. */
    private fun isAllowedIn(allowedIn: List<String>, parent: String?): Boolean =
        if (parent == null) ROOT in allowedIn else parent in allowedIn

    private fun declarativeBlockMarkdown(
        kind: String,
        name: String,
        description: String,
        allowedIn: List<String>,
        documentationUrl: String?,
    ): String = markdown {
        h2("Declarative $kind `$name`")
        text(description)
        if (allowedIn.isNotEmpty()) text("**Allowed in:** ${allowedIn.joinToString { "`$it`" }}")
        documentationUrl?.let { text("[Documentation]($it)") }
    }

    /**
     * Names of the calls whose closures contain [call], innermost first (`[options, pipeline]` for
     * `pipeline { options { timeout(...) } }`).
     */
    private fun enclosingCallNames(call: MethodCallExpression, astModel: GroovyAstModel): List<EnclosingBlock> {
        val blocks = mutableListOf<EnclosingBlock>()
        var insideClosure = false
        var viaMapValue = false
        var current: ASTNode? = astModel.getParent(call)
        while (current != null) {
            when {
                current is ClosureExpression -> {
                    insideClosure = true
                    viaMapValue = false
                }
                insideClosure && (current is MapEntryExpression || current is MapExpression) -> viaMapValue = true
                insideClosure && current is MethodCallExpression -> {
                    current.methodAsString?.let { blocks += EnclosingBlock(it, isBlockArgument = !viaMapValue) }
                    insideClosure = false
                }
            }
            current = astModel.getParent(current)
        }
        return blocks
    }

    /** A call whose closure contains the hovered call; [isBlockArgument] is false for `call(key: { ... })`. */
    private data class EnclosingBlock(val name: String, val isBlockArgument: Boolean)

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

        val hoverRange = hoverRangeOf(node)

        return Hover().apply {
            contents = Either.forRight(markupContent)
            range = hoverRange
        }
    }

    /**
     * Try to create a hover for a Jenkins step from bundled metadata.
     */
    private fun tryCreateStepMetadataHover(stepName: String, node: MethodCallExpression, uri: URI): Hover? {
        val metadata = jenkinsCapabilities?.getAllMetadata(uri) ?: return null
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
        val hoverRange = hoverRangeOf(node)

        return Hover().apply {
            contents = Either.forRight(markupContent)
            range = hoverRange
        }
    }

    /**
     * Hover range of the call, or null for synthetic nodes without a source position (Groovy reports -1), so
     * that no negative LSP coordinate is ever emitted. Groovy's `lastColumnNumber` is 1-based and EXCLUSIVE; the
     * 0-based exclusive LSP end is `lastColumnNumber - 1`.
     */
    private fun hoverRangeOf(node: ASTNode): Range? {
        val positions = listOf(node.lineNumber, node.columnNumber, node.lastLineNumber, node.lastColumnNumber)
        if (positions.any { it < 1 }) return null
        return Range(
            Position(node.lineNumber - 1, node.columnNumber - 1),
            Position(node.lastLineNumber - 1, node.lastColumnNumber - 1),
        )
    }

    private companion object {
        const val OPTIONS_BLOCK = "options"
        const val PIPELINE = "pipeline"
        const val ROOT = "root"
        val SCRIPTED_BODIES = setOf("script", "node")
    }
}
