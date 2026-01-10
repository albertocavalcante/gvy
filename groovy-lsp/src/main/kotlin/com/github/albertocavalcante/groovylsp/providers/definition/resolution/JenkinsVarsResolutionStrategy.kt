package com.github.albertocavalcante.groovylsp.providers.definition.resolution

import com.github.albertocavalcante.groovylsp.project.JenkinsCapabilities
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionResolver
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.slf4j.LoggerFactory

/**
 * Resolves Jenkins vars/ global variables with resilient navigation support.
 *
 * Jenkins Shared Libraries define global variables as files in the `vars/` directory.
 * This strategy handles multiple access patterns:
 * - Method calls: `buildPlugin()` navigates to `vars/buildPlugin.groovy`
 * - Variable references: `infra` in `infra.checkoutSCM()` navigates to `vars/infra.groovy`
 *
 * **Priority: HIGHEST** - runs before any other resolution to catch Jenkins-specific patterns.
 *
 * ## Node Handling
 * Supports both [MethodCallExpression] and [VariableExpression] to enable resilient navigation
 * for all Jenkins global variable usage patterns. Intentionally excludes ConstantExpression
 * to avoid false positives on string literals.
 */
class JenkinsVarsResolutionStrategy(private val jenkinsCapabilities: JenkinsCapabilities) : SymbolResolutionStrategy {

    private val logger = LoggerFactory.getLogger(JenkinsVarsResolutionStrategy::class.java)

    override suspend fun resolve(context: ResolutionContext): ResolutionResult {
        val varName = extractVariableName(context.targetNode)
            ?: return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)

        val globalVars = jenkinsCapabilities.getGlobalVariables()
        val matchingVar = globalVars.find { it.name == varName }
            ?: return SymbolResolutionStrategy.notFound(
                "No vars/$varName.groovy found (${globalVars.size} vars available)",
                STRATEGY_NAME,
            )

        logger.debug("Found Jenkins global variable '{}' at {}", varName, matchingVar.path)

        // Create a synthetic ClassNode to represent the definition location.
        // Uses the callLineNumber parsed from the vars file to navigate directly to `def call(...)`.
        val callLine = matchingVar.callLineNumber
        val syntheticNode = ClassNode(matchingVar.name, 0, null).apply {
            lineNumber = callLine
            columnNumber = 1
            lastLineNumber = callLine
            lastColumnNumber = 1
        }

        return SymbolResolutionStrategy.found(
            DefinitionResolver.DefinitionResult.Source(syntheticNode, matchingVar.path.toUri()),
        )
    }

    /**
     * Extract a variable name from the target node for vars/ lookup.
     *
     * Supports:
     * - [MethodCallExpression]: Extracts the method name (e.g., `buildPlugin` from `buildPlugin()`)
     * - [VariableExpression]: Extracts the variable name (e.g., `infra` from `infra.checkoutSCM()`)
     *
     * Returns null for unsupported node types (e.g., ConstantExpression).
     */
    private fun extractVariableName(node: ASTNode): String? = when (node) {
        is MethodCallExpression -> node.methodAsString
        is VariableExpression -> node.name
        else -> null
    }

    companion object {
        private const val STRATEGY_NAME = "JenkinsVars"
    }
}
