package com.github.albertocavalcante.gvy.gls.providers.hover.strategies

import com.github.albertocavalcante.groovylsp.markdown.dsl.markdown
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.config.ModeResolver
import com.github.albertocavalcante.gvy.gls.project.JenkinsCapabilities
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverContext
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverStrategy
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedGlobalVariable
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedParameter
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.DynamicVariable
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Hover for the parts of a Jenkinsfile that are not step calls themselves:
 * - a named argument of a step, declarative option or directive entry (`sh(returnStdout: true)`,
 *   `parameters { string(trim: true) }`) shows that parameter's type, default and description;
 * - a Jenkins global variable (`env`, `currentBuild`, `params`) shows its documentation.
 *
 * Only active in Jenkins mode. A local variable that shadows a global keeps the ordinary hover.
 */
class JenkinsParameterHoverStrategy(
    private val jenkinsCapabilities: JenkinsCapabilities?,
    private val modeResolver: ModeResolver,
) : HoverStrategy {

    override fun canHandle(node: ASTNode): Boolean =
        jenkinsCapabilities != null && (node is ConstantExpression || node is VariableExpression)

    override fun generateHover(node: ASTNode, context: HoverContext): Hover? {
        if (!modeResolver.isJenkinsModeEnabled(context.documentUri)) return null
        val metadata = jenkinsCapabilities?.getAllMetadata() ?: return null
        return when (node) {
            is ConstantExpression -> context.astModel?.let { parameterHover(node, it, metadata) }
            is VariableExpression -> globalVariableHover(node, metadata)
            else -> null
        }
    }

    private fun parameterHover(
        key: ConstantExpression,
        astModel: GroovyAstModel,
        metadata: MergedJenkinsMetadata,
    ): Hover? {
        if (!isMapKey(key, astModel)) return null
        val name = key.value as? String ?: return null
        val call = generateSequence(astModel.getParent(key)) { astModel.getParent(it) }
            .takeWhile { it !is ClosureExpression }
            .firstOrNull { it is MethodCallExpression } as? MethodCallExpression ?: return null
        val callName = call.methodAsString ?: return null
        val parameter = parametersOf(callName, enclosingCallName(call, astModel), metadata)?.get(name) ?: return null

        return hover(parameterMarkdown(callName, parameter), key)
    }

    /**
     * True when [key] is the key of a map entry. The AST model may link a key straight to its map
     * (`NamedArgumentListExpression`) or through its `MapEntryExpression`.
     */
    private fun isMapKey(key: ConstantExpression, astModel: GroovyAstModel): Boolean =
        when (val parent = astModel.getParent(key)) {
            is MapEntryExpression -> parent.keyExpression === key
            is MapExpression -> parent.mapEntryExpressions.any { it.keyExpression === key }
            else -> false
        }

    /**
     * Parameters of [callName]: a directive entry wins inside its directive (`string` in `parameters {}` is
     * the string parameter), then a step, then a declarative option.
     */
    private fun parametersOf(
        callName: String,
        directive: String?,
        metadata: MergedJenkinsMetadata,
    ): Map<String, MergedParameter>? = directive?.let { metadata.directiveEntries[it]?.get(callName)?.parameters }
        ?: metadata.getStep(callName)?.namedParams
        ?: metadata.getDeclarativeOption(callName)?.parameters

    /** Name of the call whose closure contains [call] (`parameters` for `parameters { string(...) }`). */
    private fun enclosingCallName(call: MethodCallExpression, astModel: GroovyAstModel): String? =
        generateSequence(astModel.getParent(call)) { astModel.getParent(it) }
            .dropWhile { it !is ClosureExpression }
            .firstOrNull { it is MethodCallExpression }
            ?.let { (it as MethodCallExpression).methodAsString }

    private fun parameterMarkdown(callName: String, parameter: MergedParameter): String = markdown {
        h2("Parameter `${parameter.name}` of `$callName`")
        val required = if (parameter.required) " *(required)*" else ""
        val default = parameter.defaultValue?.let { " (default: `$it`)" } ?: ""
        text("**Type:** `${parameter.type}`$required$default")
        parameter.description?.let(::text)
        parameter.validValues?.takeIf { it.isNotEmpty() }?.let { values ->
            text("**Valid values:** ${values.joinToString { "`$it`" }}")
        }
    }

    private fun globalVariableHover(node: VariableExpression, metadata: MergedJenkinsMetadata): Hover? {
        // A declared local or parameter shadows the global; only unresolved names are Jenkins globals.
        if (node.accessedVariable != null && node.accessedVariable !is DynamicVariable) return null
        val global = metadata.getGlobalVariable(node.name) ?: return null
        return hover(globalVariableMarkdown(global), node)
    }

    private fun globalVariableMarkdown(global: MergedGlobalVariable): String = markdown {
        h2("Jenkins Global Variable: `${global.name}`")
        text("**Type:** `${global.type}`")
        (global.enrichedDescription ?: global.extractedDocumentation)?.let(::text)
        if (global.properties.isNotEmpty()) {
            text("**Properties:** ${global.properties.keys.sorted().joinToString { "`$it`" }}")
        }
        global.documentationUrl?.let { text("[Documentation]($it)") }
    }

    private fun hover(markdownContent: String, node: ASTNode): Hover = Hover().apply {
        contents = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, markdownContent))
        range = rangeOf(node)
    }

    /** Range of [node], or null for synthetic nodes (Groovy reports -1). Groovy's end column is exclusive. */
    private fun rangeOf(node: ASTNode): Range? {
        val positions = listOf(node.lineNumber, node.columnNumber, node.lastLineNumber, node.lastColumnNumber)
        if (positions.any { it < 1 }) return null
        return Range(
            Position(node.lineNumber - 1, node.columnNumber - 1),
            Position(node.lastLineNumber - 1, node.lastColumnNumber - 1),
        )
    }
}
