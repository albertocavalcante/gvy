package com.github.albertocavalcante.groovylsp.providers.signature

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.errors.GroovyLspException
import com.github.albertocavalcante.groovylsp.util.GroovyBuiltinMethods
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Provides signature help for Groovy method calls.
 *
 * Implementation follows IntelliJ patterns:
 * - Multi-layered method call detection (ArgumentList → MethodCall → fallback)
 * - Sophisticated parameter counting with Groovy-specific handling
 * - Integration with existing AST infrastructure
 */
class SignatureHelpProvider(private val compilationService: GroovyCompilationService) {

    private val logger = LoggerFactory.getLogger(SignatureHelpProvider::class.java)

    /**
     * Provide signature help for the method call at the given position.
     * Returns null if no signature help is available.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun provideSignatureHelp(uri: String, position: Position): SignatureHelp? = withContext(Dispatchers.Default) {
        try {
            logger.debug("Providing signature help for $uri at ${position.line}:${position.character}")

            val documentUri = URI.create(uri)
            val methodCallContext = findMethodCallContext(documentUri, position)

            if (methodCallContext == null) {
                logger.debug("No method call context found at position")
                return@withContext null
            }

            logger.debug("Found method call context: ${methodCallContext.methodName}")

            val signatures = extractSignatures(methodCallContext)
            if (signatures.isEmpty()) {
                logger.debug("No signatures found for method: ${methodCallContext.methodName}")
                return@withContext null
            }

            val activeParameter = calculateActiveParameter(methodCallContext, position)

            SignatureHelp().apply {
                this.signatures = signatures
                this.activeSignature = 0 // Default to first signature
                this.activeParameter = activeParameter
            }
        } catch (e: GroovyLspException) {
            logger.debug("LSP error providing signature help: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid arguments for signature help: ${e.message}")
            null
        } catch (e: Exception) {
            logger.error("Unexpected error providing signature help for $uri at ${position.line}:${position.character}", e)
            null
        }
    }

    /**
     * Find the method call context at the given position.
     * Uses multi-layered detection following IntelliJ patterns.
     */
    private fun findMethodCallContext(documentUri: URI, position: Position): MethodCallContext? {
        val astVisitor = compilationService.getAstVisitor(documentUri) ?: return null

        // Primary: Look for ArgumentList containing cursor
        val nodeAtPosition = astVisitor.getNodeAt(documentUri, position)
        if (nodeAtPosition is ArgumentListExpression) {
            val parent = astVisitor.getParent(nodeAtPosition)
            if (parent is MethodCallExpression) {
                return createMethodCallContext(parent, nodeAtPosition, position)
            }
        }

        // Secondary: Find MethodCall parent and check if cursor is within argument list
        val methodCall = findEnclosingMethodCall(nodeAtPosition, astVisitor)
        if (methodCall != null) {
            val argumentList = methodCall.arguments as? ArgumentListExpression
            if (argumentList != null && isPositionInArgumentList(argumentList, position)) {
                return createMethodCallContext(methodCall, argumentList, position)
            }
        }

        // Fallback: Look backward for incomplete method calls
        return findIncompleteMethodCall(documentUri, position, astVisitor)
    }

    /**
     * Find enclosing method call by traversing up the AST.
     */
    private fun findEnclosingMethodCall(node: ASTNode?, astVisitor: com.github.albertocavalcante.groovylsp.ast.AstVisitor): MethodCallExpression? {
        var current = node
        while (current != null) {
            if (current is MethodCallExpression) {
                return current
            }
            current = astVisitor.getParent(current)
        }
        return null
    }

    /**
     * Check if the position is within the argument list bounds.
     */
    private fun isPositionInArgumentList(argumentList: ArgumentListExpression, position: Position): Boolean {
        // Convert LSP position to Groovy coordinates for comparison
        val groovyLine = position.line + 1
        val groovyColumn = position.character + 1

        return groovyLine >= argumentList.lineNumber &&
               groovyLine <= argumentList.lastLineNumber &&
               (groovyLine > argumentList.lineNumber || groovyColumn >= argumentList.columnNumber) &&
               (groovyLine < argumentList.lastLineNumber || groovyColumn <= argumentList.lastColumnNumber)
    }

    /**
     * Create method call context from a method call expression.
     */
    private fun createMethodCallContext(
        methodCall: MethodCallExpression,
        argumentList: ArgumentListExpression,
        position: Position
    ): MethodCallContext {
        val methodName = when (val method = methodCall.method) {
            is ConstantExpression -> method.text ?: "unknown"
            else -> method.text ?: "unknown"
        }

        return MethodCallContext(
            methodName = methodName,
            methodCall = methodCall,
            argumentList = argumentList,
            cursorPosition = position
        )
    }

    /**
     * Find incomplete method calls for graceful handling during typing.
     * This is a simplified fallback - can be enhanced later.
     */
    private fun findIncompleteMethodCall(
        documentUri: URI,
        position: Position,
        astVisitor: com.github.albertocavalcante.groovylsp.ast.AstVisitor
    ): MethodCallContext? {
        // For now, return null - this can be enhanced to handle incomplete syntax
        logger.debug("Fallback incomplete method call detection not yet implemented")
        return null
    }

    /**
     * Extract signatures for the method call context.
     */
    private fun extractSignatures(context: MethodCallContext): List<SignatureInformation> {
        val signatures = mutableListOf<SignatureInformation>()

        // Check if it's a Groovy builtin method
        if (GroovyBuiltinMethods.isBuiltinMethod(context.methodName)) {
            val builtinSignature = createBuiltinMethodSignature(context.methodName)
            if (builtinSignature != null) {
                signatures.add(builtinSignature)
            }
        }

        // Find method definitions in the AST
        val astVisitor = compilationService.getAstVisitor(URI.create("dummy")) // Get any visitor to access methods
        if (astVisitor != null) {
            val methodNodes = findMethodDefinitions(context.methodName, astVisitor)
            methodNodes.forEach { methodNode ->
                val signature = createSignatureFromMethodNode(methodNode)
                signatures.add(signature)
            }
        }

        return signatures
    }

    /**
     * Create signature for Groovy builtin methods.
     */
    private fun createBuiltinMethodSignature(methodName: String): SignatureInformation? {
        val description = GroovyBuiltinMethods.getMethodDescription(methodName)
        val category = GroovyBuiltinMethods.getMethodCategory(methodName)

        return when (methodName) {
            "println" -> SignatureInformation().apply {
                label = "println(value: Object): void"
                documentation = Either.forLeft("$description\n\nCategory: $category")
                parameters = listOf(
                    ParameterInformation().apply {
                        label = Either.forLeft("value: Object")
                        documentation = Either.forLeft("The value to print followed by a newline")
                    }
                )
            }
            "print" -> SignatureInformation().apply {
                label = "print(value: Object): void"
                documentation = Either.forLeft("$description\n\nCategory: $category")
                parameters = listOf(
                    ParameterInformation().apply {
                        label = Either.forLeft("value: Object")
                        documentation = Either.forLeft("The value to print without a newline")
                    }
                )
            }
            "each" -> SignatureInformation().apply {
                label = "each(closure: Closure): Object"
                documentation = Either.forLeft("$description\n\nCategory: $category")
                parameters = listOf(
                    ParameterInformation().apply {
                        label = Either.forLeft("closure: Closure")
                        documentation = Either.forLeft("Closure to execute for each element")
                    }
                )
            }
            "collect" -> SignatureInformation().apply {
                label = "collect(closure: Closure): List"
                documentation = Either.forLeft("$description\n\nCategory: $category")
                parameters = listOf(
                    ParameterInformation().apply {
                        label = Either.forLeft("closure: Closure")
                        documentation = Either.forLeft("Transformation closure to apply to each element")
                    }
                )
            }
            else -> SignatureInformation().apply {
                label = "$methodName(...)"
                documentation = Either.forLeft("$description\n\nCategory: $category")
                parameters = emptyList()
            }
        }
    }

    /**
     * Find method definitions by name in the AST.
     */
    private fun findMethodDefinitions(
        methodName: String,
        astVisitor: com.github.albertocavalcante.groovylsp.ast.AstVisitor
    ): List<MethodNode> {
        return astVisitor.getAllNodes()
            .filterIsInstance<MethodNode>()
            .filter { it.name == methodName }
    }

    /**
     * Create signature information from a method node.
     */
    private fun createSignatureFromMethodNode(methodNode: MethodNode): SignatureInformation {
        val parameterLabels = methodNode.parameters.map { param ->
            "${param.type.nameWithoutPackage} ${param.name}${if (param.hasInitialExpression()) " = ${param.initialExpression?.text}" else ""}"
        }

        val label = "${methodNode.name}(${parameterLabels.joinToString(", ")}): ${methodNode.returnType.nameWithoutPackage}"

        val parameters = methodNode.parameters.map { param ->
            createParameterInformation(param)
        }

        return SignatureInformation().apply {
            this.label = label
            this.documentation = Either.forLeft("Method: ${methodNode.name}")
            this.parameters = parameters
        }
    }

    /**
     * Create parameter information from a method parameter.
     */
    private fun createParameterInformation(param: Parameter): ParameterInformation {
        val paramLabel = "${param.type.nameWithoutPackage} ${param.name}${if (param.hasInitialExpression()) " = ${param.initialExpression?.text}" else ""}"

        return ParameterInformation().apply {
            label = Either.forLeft(paramLabel)
            documentation = Either.forLeft(
                "Parameter: ${param.name} of type ${param.type.nameWithoutPackage}",
            )
        }
    }

    /**
     * Calculate the active parameter index based on cursor position.
     * Handles Groovy-specific features like named parameters.
     */
    private fun calculateActiveParameter(context: MethodCallContext, position: Position): Int {
        val argumentList = context.argumentList
        val arguments = argumentList.expressions

        if (arguments.isEmpty()) {
            return 0
        }

        // Convert position to Groovy coordinates
        val groovyLine = position.line + 1
        val groovyColumn = position.character + 1

        // Count arguments before the cursor position
        var parameterIndex = 0

        for ((index, argument) in arguments.withIndex()) {
            // Check if cursor is before this argument
            if (groovyLine < argument.lineNumber ||
                (groovyLine == argument.lineNumber && groovyColumn < argument.columnNumber)) {
                break
            }

            // Check if cursor is within this argument
            if (groovyLine >= argument.lineNumber && groovyLine <= argument.lastLineNumber &&
                (groovyLine > argument.lineNumber || groovyColumn >= argument.columnNumber) &&
                (groovyLine < argument.lastLineNumber || groovyColumn <= argument.lastColumnNumber)) {
                parameterIndex = index
                break
            }

            // Cursor is after this argument
            parameterIndex = index + 1
        }

        // Handle named parameters (Groovy map-style parameters)
        // In Groovy, named parameters are typically the first parameter as a map
        // For now, we'll handle this simply - can be enhanced later

        return parameterIndex.coerceAtMost(arguments.size)
    }
}

/**
 * Context information for a method call at a specific position.
 */
data class MethodCallContext(
    val methodName: String,
    val methodCall: MethodCallExpression,
    val argumentList: ArgumentListExpression,
    val cursorPosition: Position
)