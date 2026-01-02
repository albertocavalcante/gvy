package com.github.albertocavalcante.groovylsp.providers.inlayhints

import com.github.albertocavalcante.groovycommon.text.formatTypeName
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.InlayHintsConfiguration
import com.github.albertocavalcante.groovyparser.ast.TypeInferencer
import com.github.albertocavalcante.groovyparser.ast.isDynamic
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Provider for LSP Inlay Hints.
 *
 * Inlay hints are inline annotations that display:
 * - **Type hints**: Inferred types for `def` variable declarations
 * - **Parameter hints**: Parameter names at method/constructor call sites
 *
 * Implementation inspired by:
 * - kotlin-language-server: Clean enum-based kind mapping, AST traversal
 * - eclipse.jdt.ls: Visitor pattern, range filtering, smart suppression rules
 *
 * @see <a href="https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_inlayHint">LSP Inlay Hints Specification</a>
 */
class InlayHintsProvider(
    private val compilationService: GroovyCompilationService,
    private val configuration: InlayHintsConfiguration = InlayHintsConfiguration(),
) {
    private val logger = LoggerFactory.getLogger(InlayHintsProvider::class.java)

    /**
     * Provide inlay hints for the requested range.
     *
     * @param params The request parameters containing document URI and range
     * @return List of inlay hints within the requested range
     */
    fun provideInlayHints(params: InlayHintParams): List<InlayHint> {
        val uri = URI.create(params.textDocument.uri)
        val astModel = compilationService.getAstModel(uri)

        if (astModel == null) {
            logger.debug("No AST model available for $uri")
            return emptyList()
        }

        val hints = mutableListOf<InlayHint>()
        val range = params.range

        // Traverse all nodes and collect hints within the requested range
        astModel.getAllNodes().forEach { node ->
            // Filter nodes outside the requested range (1-indexed to 0-indexed conversion)
            val nodeLine = node.lineNumber - 1
            if (nodeLine < range.start.line || nodeLine > range.end.line) {
                return@forEach
            }

            when (node) {
                is DeclarationExpression -> {
                    if (configuration.typeHints) {
                        collectTypeHint(node)?.let { hints.add(it) }
                    }
                }

                is MethodCallExpression -> {
                    if (configuration.parameterHints) {
                        collectParameterHints(node, uri, hints)
                    }
                }

                is ConstructorCallExpression -> {
                    if (configuration.parameterHints) {
                        collectConstructorParameterHints(node, uri, hints)
                    }
                }
            }
        }

        logger.debug("Returning ${hints.size} inlay hints for ${params.textDocument.uri}")
        return hints
    }

    /**
     * Collect type hint for a declaration expression (e.g., `def name = "hello"`).
     *
     * Only shows hints for `def` declarations where the type is inferred.
     */
    private fun collectTypeHint(decl: DeclarationExpression): InlayHint? {
        val varExpr = decl.leftExpression as? VariableExpression ?: return null

        // Only show type hints for dynamic/def declarations
        if (!varExpr.type.isDynamic()) {
            return null
        }

        // Use TypeInferencer to get the inferred type
        val inferredType = TypeInferencer.inferType(decl)
        if (inferredType == "java.lang.Object") {
            // Don't show hints for Object (no useful information)
            return null
        }

        // Format type as simple name (e.g., "ArrayList<Integer>" instead of "java.util.ArrayList<Integer>")
        val displayType = inferredType.formatTypeName()

        // Position the hint after the variable name
        val position = Position(
            varExpr.lineNumber - 1, // Convert to 0-indexed
            varExpr.columnNumber + varExpr.name.length - 1,
        )

        return InlayHint(position, Either.forLeft(": $displayType")).apply {
            kind = InlayHintKind.Type
            paddingLeft = true
        }
    }

    /**
     * Collect parameter hints for a method call expression.
     *
     * Shows parameter names at call sites for positional arguments.
     */
    private fun collectParameterHints(call: MethodCallExpression, uri: URI, hints: MutableList<InlayHint>) {
        val arguments = call.arguments as? ArgumentListExpression ?: return

        if (arguments.expressions.isEmpty()) {
            return
        }

        // Try to resolve the method to get parameter names
        val parameterNames = resolveMethodParameterNames(call, uri)
        if (parameterNames.isEmpty()) {
            return
        }

        // Generate hints for each argument
        arguments.expressions.forEachIndexed { index, arg ->
            if (index >= parameterNames.size) return@forEachIndexed

            val paramName = parameterNames[index]

            // Skip if argument name matches parameter name (no useful info)
            if (isSameNameAsArgument(arg, paramName)) {
                return@forEachIndexed
            }

            // Skip if argument is a closure (they provide their own context)
            if (arg is ClosureExpression) {
                return@forEachIndexed
            }

            val position = Position(
                arg.lineNumber - 1,
                arg.columnNumber - 1,
            )

            hints.add(
                InlayHint(position, Either.forLeft("$paramName:")).apply {
                    kind = InlayHintKind.Parameter
                    paddingRight = true
                },
            )
        }
    }

    /**
     * Collect parameter hints for a constructor call expression.
     */
    private fun collectConstructorParameterHints(
        call: ConstructorCallExpression,
        uri: URI,
        hints: MutableList<InlayHint>,
    ) {
        val arguments = call.arguments as? ArgumentListExpression ?: return

        if (arguments.expressions.isEmpty()) {
            return
        }

        // Try to resolve constructor parameter names
        val parameterNames = resolveConstructorParameterNames(call, uri)
        if (parameterNames.isEmpty()) {
            return
        }

        arguments.expressions.forEachIndexed { index, arg ->
            if (index >= parameterNames.size) return@forEachIndexed

            val paramName = parameterNames[index]

            if (isSameNameAsArgument(arg, paramName)) {
                return@forEachIndexed
            }

            if (arg is ClosureExpression) {
                return@forEachIndexed
            }

            val position = Position(
                arg.lineNumber - 1,
                arg.columnNumber - 1,
            )

            hints.add(
                InlayHint(position, Either.forLeft("$paramName:")).apply {
                    kind = InlayHintKind.Parameter
                    paddingRight = true
                },
            )
        }
    }

    /**
     * Check if the argument expression has the same name as the parameter.
     *
     * This follows eclipse.jdt.ls pattern of suppressing hints when they're redundant.
     */
    private fun isSameNameAsArgument(arg: Expression, paramName: String): Boolean {
        return when (arg) {
            is VariableExpression -> arg.name == paramName
            is ConstantExpression -> {
                // For string literals that look like the parameter name
                val text = arg.value?.toString() ?: return false
                text == paramName
            }

            else -> false
        }
    }

    /**
     * Resolve parameter names for a method call.
     *
     * Searches the current AST for matching method declarations.
     *
     * TODO(#568): Support cross-file parameter name resolution.
     *   See: https://github.com/albertocavalcante/gvy/issues/568
     */
    private fun resolveMethodParameterNames(call: MethodCallExpression, uri: URI): List<String> {
        val astModel = compilationService.getAstModel(uri) ?: return emptyList()
        val methodName = call.methodAsString ?: return emptyList()

        // Search for matching method declaration
        astModel.getAllClassNodes().forEach { classNode ->
            classNode.methods
                .filter { it.name == methodName }
                .forEach { method ->
                    val argCount = (call.arguments as? ArgumentListExpression)?.expressions?.size ?: 0
                    if (method.parameters.size == argCount) {
                        return method.parameters.map { it.name }
                    }
                }
        }

        return emptyList()
    }

    /**
     * Resolve parameter names for a constructor call.
     *
     * TODO(#568): Support cross-file parameter name resolution.
     *   See: https://github.com/albertocavalcante/gvy/issues/568
     */
    private fun resolveConstructorParameterNames(call: ConstructorCallExpression, uri: URI): List<String> {
        val astModel = compilationService.getAstModel(uri) ?: return emptyList()
        val typeName = call.type.nameWithoutPackage

        // Search for matching constructor
        astModel.getAllClassNodes()
            .filter { it.nameWithoutPackage == typeName }
            .forEach { classNode ->
                classNode.declaredConstructors.forEach { constructor ->
                    val argCount = (call.arguments as? ArgumentListExpression)?.expressions?.size ?: 0
                    if (constructor.parameters.size == argCount) {
                        return constructor.parameters.map { it.name }
                    }
                }
            }

        return emptyList()
    }
}
