package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovyjenkins.metadata.JenkinsBlockMetadata
import com.github.albertocavalcante.groovyjenkins.metadata.MergedGlobalVariable
import com.github.albertocavalcante.groovyjenkins.metadata.MergedJenkinsMetadata
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.dsl.completion.CompletionsBuilder
import com.github.albertocavalcante.groovylsp.dsl.completion.completion
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.eclipse.lsp4j.CompletionItemKind

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
        compilationService: GroovyCompilationService,
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
        val varsGlobals = compilationService.workspaceManager.getJenkinsGlobalVariables()
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
        val methodCall = CompletionContextDetector.findEnclosingMethodCall(nodeAtCursor, astModel)
        val callName = methodCall?.methodAsString ?: return

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

        val bundledParamCompletions = JenkinsStepCompletionProvider.getParameterCompletions(
            callName,
            existingKeys,
            metadata,
            CompletionContextDetector.isCommandExpression(ctx.content, ctx.line, ctx.character, callName),
        )
        bundledParamCompletions.forEach(::add)
    }
}
