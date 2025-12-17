package com.github.albertocavalcante.groovylsp.providers.definition.resolution

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.providers.definition.DefinitionResolver
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.slf4j.LoggerFactory

/**
 * Resolves method calls to Jenkins vars/ global variables.
 *
 * Jenkins Shared Libraries define global variables as files in the `vars/` directory.
 * When a Jenkinsfile calls `buildPlugin()`, this should navigate to `vars/buildPlugin.groovy`.
 *
 * **Priority: HIGHEST** - runs before any other resolution to catch Jenkins-specific patterns.
 *
 * ## Node Handling
 * This strategy intentionally only triggers on [MethodCallExpression] to avoid false positives
 * when clicking on identifier-like string literals.
 */
class JenkinsVarsResolutionStrategy(private val compilationService: GroovyCompilationService) :
    SymbolResolutionStrategy {

    private val logger = LoggerFactory.getLogger(JenkinsVarsResolutionStrategy::class.java)

    override suspend fun resolve(context: ResolutionContext): ResolutionResult {
        val methodName = extractMethodName(context.targetNode)
            ?: return SymbolResolutionStrategy.notApplicable(STRATEGY_NAME)

        val globalVars = compilationService.getJenkinsGlobalVariables()
        val matchingVar = globalVars.find { it.name == methodName }
            ?: return SymbolResolutionStrategy.notFound(
                "No vars/$methodName.groovy found (${globalVars.size} vars available)",
                STRATEGY_NAME,
            )

        logger.debug("Found Jenkins global variable '{}' at {}", methodName, matchingVar.path)

        // Create a synthetic ClassNode to represent the definition location
        // NOTE: We only need a non-invalid position so LSP clients can open the target file.
        // TODO: Parse `vars/*.groovy` and use the real AST range for precise selection/navigation.
        val syntheticNode = ClassNode(matchingVar.name, 0, null).apply {
            lineNumber = 1
            columnNumber = 1
            lastLineNumber = 1
            lastColumnNumber = 1
        }

        return SymbolResolutionStrategy.found(
            DefinitionResolver.DefinitionResult.Source(syntheticNode, matchingVar.path.toUri()),
        )
    }

    /**
     * Extract a method name from the target node for vars/ lookup.
     */
    private fun extractMethodName(node: org.codehaus.groovy.ast.ASTNode): String? =
        (node as? MethodCallExpression)?.methodAsString

    companion object {
        private const val STRATEGY_NAME = "JenkinsVars"
    }
}
