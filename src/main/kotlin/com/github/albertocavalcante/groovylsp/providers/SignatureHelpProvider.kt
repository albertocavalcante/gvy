package com.github.albertocavalcante.groovylsp.providers

import com.github.albertocavalcante.groovylsp.ast.AstVisitor
import com.github.albertocavalcante.groovylsp.ast.containsPosition
import com.github.albertocavalcante.groovylsp.ast.safePosition
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.slf4j.LoggerFactory
import java.net.URI

class SignatureHelpProvider(
    private val compilationService: GroovyCompilationService,
    private val documentProvider: DocumentProvider,
) {

    private val logger = LoggerFactory.getLogger(SignatureHelpProvider::class.java)

    suspend fun provideSignatureHelp(uri: String, position: Position): SignatureHelp {
        val documentUri = URI.create(uri)
        ensureAstPrepared(documentUri)

        val astVisitor = compilationService.getAstVisitor(documentUri) ?: run {
            logger.debug("No AST visitor available for {}", uri)
            return emptySignatureHelp()
        }
        val symbolTable = compilationService.getSymbolTable(documentUri) ?: run {
            logger.debug("No symbol table available for {}", uri)
            return emptySignatureHelp()
        }

        val nodeAtPosition = astVisitor.getNodeAt(documentUri, position) ?: run {
            logger.debug("No AST node found at $position for $uri")
            return emptySignatureHelp()
        }
        logger.debug("Node at $position is ${nodeAtPosition.javaClass.simpleName}")

        val methodCall = findMethodCall(astVisitor, documentUri, nodeAtPosition, position) ?: run {
            logger.debug("No method call expression near $position for $uri")
            return emptySignatureHelp()
        }

        val methodName = methodCall.extractMethodName() ?: run {
            logger.debug("Could not resolve method name for call at $position in $uri")
            return emptySignatureHelp()
        }

        val declarations = symbolTable.registry.findMethodDeclarations(documentUri, methodName)
        if (declarations.isEmpty()) {
            logger.debug("No matching declarations found for method $methodName in $uri")
            return emptySignatureHelp()
        }

        val signatures = declarations.map { it.toSignatureInformation() }.toMutableList()
        val activeParameter = determineActiveParameter(methodCall, nodeAtPosition, position, astVisitor)
        val normalizedActiveParameter = signatures.firstOrNull()
            ?.parameters
            ?.lastIndex
            ?.takeIf { it >= 0 }
            ?.let { activeParameter.coerceIn(0, it) }
            ?: 0

        return SignatureHelp().apply {
            this.signatures = signatures
            this.activeSignature = 0
            this.activeParameter = normalizedActiveParameter
        }
    }

    private suspend fun ensureAstPrepared(uri: URI) {
        val hasAst = compilationService.getAst(uri) != null
        val hasVisitor = compilationService.getAstVisitor(uri) != null
        val hasSymbols = compilationService.getSymbolTable(uri) != null

        if (hasAst && hasVisitor && hasSymbols) {
            return
        }

        val content = documentProvider.get(uri) ?: return
        runCatching { compilationService.compile(uri, content) }
            .onFailure { error ->
                logger.debug("Unable to compile $uri before providing signature help", error)
            }
    }

    private fun MethodNode.toSignatureInformation(): SignatureInformation {
        val methodParameters = parameters
        val parametersInfo = methodParameters.map { parameter ->
            ParameterInformation().apply {
                label = Either.forLeft(parameter.toSignatureLabel())
            }
        }.toMutableList()

        return SignatureInformation().apply {
            label = buildString {
                append(name)
                append("(")
                append(methodParameters.joinToString(", ") { it.toSignatureLabel() })
                append(")")
            }
            this.parameters = parametersInfo
        }
    }

    private fun Parameter.toSignatureLabel(): String {
        val typeName = when {
            isDynamicTyped -> "def"
            else -> type.nameWithoutPackage
        }
        return buildString {
            append("$typeName $name")
            initialExpression?.takeIf { it.text.isNotBlank() }?.let { expr ->
                append(" = ${expr.text}")
            }
        }
    }

    private fun determineActiveParameter(
        methodCall: MethodCallExpression,
        nodeAtPosition: ASTNode,
        position: Position,
        astVisitor: AstVisitor,
    ): Int {
        val arguments = methodCall.argumentExpressions()

        arguments.forEachIndexed { index, argument ->
            if (argument == nodeAtPosition || astVisitor.contains(argument, nodeAtPosition)) {
                return index
            }
            if (argument.containsPosition(position.line, position.character)) {
                return index
            }
        }

        return estimateParameterIndex(arguments, position)
    }

    private fun estimateParameterIndex(arguments: List<Expression>, position: Position): Int {
        arguments.forEachIndexed { index, argument ->
            val start = argument.safePosition().getOrNull()?.toLspPosition()
            if (start != null && isBefore(position, start)) {
                return index
            }
        }
        return arguments.size
    }

    private fun isBefore(position: Position, other: Position): Boolean {
        if (position.line != other.line) {
            return position.line < other.line
        }
        return position.character < other.character
    }

    private fun findMethodCall(
        astVisitor: AstVisitor,
        documentUri: URI,
        nodeAtPosition: ASTNode,
        position: Position,
    ): MethodCallExpression? {
        var current: ASTNode? = nodeAtPosition
        while (current != null && current !is MethodCallExpression) {
            current = astVisitor.getParent(current)
        }
        if (current is MethodCallExpression) {
            return current
        }

        return astVisitor.getNodes(documentUri)
            .asSequence()
            .filterIsInstance<MethodCallExpression>()
            .firstOrNull { methodCall ->
                methodCall.containsPosition(position.line, position.character)
            }
    }

    private fun MethodCallExpression.argumentExpressions(): List<Expression> = when (val args = arguments) {
        is ArgumentListExpression -> args.expressions
        is TupleExpression -> args.expressions
        else -> emptyList()
    }

    private fun MethodCallExpression.extractMethodName(): String? {
        methodAsString?.let { return it }

        val methodExpression = method
        return when (methodExpression) {
            is ConstantExpression -> methodExpression.value?.toString()
            is VariableExpression -> methodExpression.name
            is PropertyExpression -> methodExpression.propertyAsString
            else -> null
        }
    }

    private fun emptySignatureHelp(): SignatureHelp = SignatureHelp().apply {
        signatures = mutableListOf()
    }
}
