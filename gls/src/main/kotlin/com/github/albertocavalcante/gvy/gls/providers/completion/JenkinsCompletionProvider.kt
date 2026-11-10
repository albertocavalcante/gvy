package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.gvy.gls.dsl.completion.CompletionsBuilder
import com.github.albertocavalcante.gvy.gls.dsl.completion.completion
import com.github.albertocavalcante.gvy.gls.project.JenkinsCapabilities
import com.github.albertocavalcante.gvy.jenkins.metadata.JenkinsBlockMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedGlobalVariable
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either

internal object JenkinsCompletionProvider {

    /**
     * Resolves a Jenkins global variable by name or type, with shadowing protection.
     */
    fun findJenkinsGlobalVariable(
        name: String?,
        type: String,
        metadata: MergedJenkinsMetadata,
    ): MergedGlobalVariable? {
        // 1. Try to find by name (e.g. "env")
        if (name != null) {
            val byName = metadata.getGlobalVariable(name)
            if (byName != null) {
                // VERIFICATION: Check if the inferred type matches the global variable type.
                // If it doesn't match (and isn't Object/dynamic), it's likely a shadowed variable.
                // We assume "java.lang.Object" might be an untyped 'def', so we allow it to fall back to the global.
                if (type != "java.lang.Object" && type != byName.type) {
                    return null
                }
                return byName
            }
        }

        // 2. Fallback: Find by type (e.g. "org.jenkinsci.plugins.workflow.cps.EnvActionImpl")
        // Note: metadata.globalVariables is keyed by variable name, so we scan values for matching type.
        return metadata.globalVariables.values.find { it.type == type }
    }

    fun CompletionsBuilder.addJenkinsGlobalVariables(
        metadata: MergedJenkinsMetadata,
        jenkinsCapabilities: JenkinsCapabilities?,
    ) {
        // 1. Add global variables from bundled plugin metadata
        val bundledCompletions = JenkinsStepCompletionProvider.getGlobalVariableCompletions(metadata)
        bundledCompletions.forEach { item ->
            val docString = when {
                item.documentation?.isRight == true -> item.documentation.right.value
                item.documentation?.isLeft == true -> item.documentation.left
                else -> null
            } ?: item.detail

            variable(
                name = item.label,
                type = item.detail?.substringAfterLast('.') ?: "Object",
                doc = docString ?: "Jenkins global variable",
            )
        }

        // 2. Add global variables from workspace vars/ directory
        // TODO: Consider caching these completions if performance becomes an issue
        val varsGlobals = jenkinsCapabilities?.getGlobalVariables() ?: emptyList()
        varsGlobals.forEach { globalVar ->
            // Use function() to insert as method call with parens: buildPlugin($1)
            function(
                name = globalVar.name,
                returnType = "Object",
                doc = globalVar.documentation.ifEmpty {
                    "Shared library global variable from vars/${globalVar.name}.groovy"
                },
            )
        }
    }

    fun CompletionsBuilder.addJenkinsDeclarativeOptions(metadata: MergedJenkinsMetadata) {
        val optionCompletions = JenkinsStepCompletionProvider.getDeclarativeOptionCompletions(metadata)
        optionCompletions.forEach(::add)
    }

    fun CompletionsBuilder.addJenkinsAgentTypeCompletions() {
        JenkinsBlockMetadata.AGENT_TYPES.forEach { agent ->
            completion {
                label(agent)
                kind(CompletionItemKind.Keyword)
                detail("Declarative agent type")
                documentation("Use an agent specification such as any, label, or docker.")
                insertText(agent)
                sortText("0-agent-$agent")
            }
        }
    }

    fun CompletionsBuilder.addJenkinsPostConditionCompletions() {
        JenkinsBlockMetadata.POST_CONDITIONS.forEach { condition ->
            completion {
                label(condition)
                kind(CompletionItemKind.Keyword)
                detail("Jenkins post condition")
                documentation("Post-build condition inside pipeline or stage post block")
                insertText("$condition {")
                sortText("0-post-$condition")
            }
        }
    }

    fun CompletionsBuilder.addJenkinsStepCompletions(metadata: MergedJenkinsMetadata) {
        val stepCompletions = JenkinsStepCompletionProvider.getStepCompletions(metadata)
        stepCompletions.forEach(::add)
    }

    /**
     * Add property completions for Jenkins global variables (env, currentBuild).
     */
    fun CompletionsBuilder.addJenkinsPropertyCompletions(globalVar: MergedGlobalVariable) {
        globalVar.properties.forEach { (name, prop) ->
            property(
                name = name,
                type = prop.type,
                doc = prop.description,
            )
        }
    }

    fun CompletionsBuilder.addJenkinsMapKeyCompletions(
        ctx: CompletionContext,
        nodeAtCursor: ASTNode?,
        astModel: GroovyAstModel,
        metadata: MergedJenkinsMetadata,
    ) {
        // After a complete command-expression argument the dummy identifier turns `sh 'ls' |` into the
        // property access `sh('ls').<dummy>`. That receiver is the step call only when there is no real dot;
        // after `sh(...).trim|` or `readFile('x').|` the receiver call is not a step being configured.
        val afterDot = followsMemberAccessDot(ctx.content, ctx.line, ctx.character)
        val methodCall = generateSequence(nodeAtCursor) { astModel.getParent(it) }
            // Inside a step's closure body (`stage('x') { | }`, `node { | }`) the step's own map keys do
            // not apply: the body holds statements, not that call's arguments.
            .takeWhile { it !is ClosureExpression }
            .firstNotNullOfOrNull { node ->
                callOrReceiverCall(node) { receiver ->
                    // The receiver is the step being configured only for a paren-less command expression
                    // (`sh 'ls' |`); `sh('ls') |` and `sh(...).x|` are complete / member-access cases.
                    !afterDot && isCommandAt(ctx, receiver)
                }
            }
        val callName = methodCall?.methodAsString ?: return

        // Never edit the inside of a string literal (e.g. `sh 'ls|'`).
        if (CompletionContextDetector.isInsideStringLiteral(ctx.content, ctx.line, ctx.character)) return

        // Collect already specified argument map keys if present
        val existingKeys = mutableSetOf<String>()
        val args = methodCall.arguments
        if (args is ArgumentListExpression) {
            args.expressions.filterIsInstance<MapExpression>().forEach { mapExpr ->
                mapExpr.mapEntryExpressions.forEach { entry ->
                    val key = entry.keyExpression.text.removeSuffix(":")
                    existingKeys.add(key)
                }
            }
        }

        val isCommand = CompletionContextDetector.isCommandExpression(ctx.content, ctx.line, ctx.character, callName)
        val bundledParamCompletions = JenkinsStepCompletionProvider.getParameterCompletions(
            callName,
            existingKeys,
            metadata,
            isCommand,
        )
        val typedWord = if (isCommand) typedWordBeforeCursor(ctx) else null
        bundledParamCompletions.forEach { item ->
            // A partially typed option (`sh 'ls' ret|`) must be replaced together with the `, ` separator.
            if (!typedWord.isNullOrEmpty()) {
                val range = Range(
                    Position(ctx.line, ctx.character - typedWord.length),
                    Position(ctx.line, ctx.character),
                )
                item.filterText = item.label
                item.textEdit = Either.forLeft(TextEdit(range, item.insertText))
            }
            add(item)
        }
    }

    private fun typedWordBeforeCursor(ctx: CompletionContext): String {
        val lineText = ctx.content.lines().getOrNull(ctx.line).orEmpty()
        val prefix = lineText.take(ctx.character.coerceIn(0, lineText.length))
        return prefix.takeLastWhile { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }
    }

    private fun isCommandAt(ctx: CompletionContext, call: MethodCallExpression): Boolean {
        val name = call.methodAsString ?: return false
        return CompletionContextDetector.isCommandExpression(ctx.content, ctx.line, ctx.character, name)
    }

    private fun callOrReceiverCall(
        node: ASTNode,
        acceptReceiver: (MethodCallExpression) -> Boolean,
    ): MethodCallExpression? {
        if (node is MethodCallExpression) return node
        val receiver = (node as? PropertyExpression)?.objectExpression as? MethodCallExpression
        return receiver?.takeIf(acceptReceiver)
    }
}
