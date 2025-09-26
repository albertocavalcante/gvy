package com.github.albertocavalcante.groovylsp.ast

import com.github.albertocavalcante.groovylsp.errors.LspError
import com.github.albertocavalcante.groovylsp.errors.LspResult
import com.github.albertocavalcante.groovylsp.errors.flatMapResult
import com.github.albertocavalcante.groovylsp.errors.toLspResult
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URI

/**
 * Type-safe wrappers for position coordinates
 */
@JvmInline
value class LineNumber(val value: Int) {
    init {
        require(value >= 0) { "Line number must be non-negative, got $value" }
    }

    fun toLspLine(): Int = value
    fun toAstLine(): Int = value + 1

    companion object {
        fun fromLsp(lspLine: Int): LineNumber = LineNumber(lspLine)
        fun fromAst(astLine: Int): LineNumber = LineNumber(maxOf(0, astLine - 1))
    }
}

@JvmInline
value class ColumnNumber(val value: Int) {
    init {
        require(value >= 0) { "Column number must be non-negative, got $value" }
    }

    fun toLspColumn(): Int = value
    fun toAstColumn(): Int = value + 1

    companion object {
        fun fromLsp(lspColumn: Int): ColumnNumber = ColumnNumber(lspColumn)
        fun fromAst(astColumn: Int): ColumnNumber = ColumnNumber(maxOf(0, astColumn - 1))
    }
}

/**
 * Type-safe position with validation
 */
data class SafePosition(val line: LineNumber, val column: ColumnNumber) {
    fun toLspPosition(): Position = Position(line.toLspLine(), column.toLspColumn())

    fun isWithin(start: SafePosition, end: SafePosition): Boolean = when {
        line.value < start.line.value -> false
        line.value > end.line.value -> false
        line.value == start.line.value && column.value < start.column.value -> false
        line.value == end.line.value && column.value > end.column.value -> false
        else -> true
    }

    companion object {
        fun fromLsp(position: Position): SafePosition = SafePosition(
            LineNumber.fromLsp(position.line),
            ColumnNumber.fromLsp(position.character),
        )

        fun fromAst(line: Int, column: Int): LspResult<SafePosition> = if (line > 0 && column > 0) {
            SafePosition(
                LineNumber.fromAst(line),
                ColumnNumber.fromAst(column),
            ).toLspResult()
        } else {
            LspError.InvalidPosition(
                URI.create("unknown"),
                line,
                column,
                "Invalid AST coordinates",
            ).toLspResult()
        }
    }
}

/**
 * Sealed class hierarchy for type-safe node references
 */
sealed class NodeReference {
    abstract val name: String
    abstract val uri: URI?

    data class Variable(override val name: String, override val uri: URI?, val type: ClassNode?, val node: ASTNode) :
        NodeReference()

    data class Method(
        override val name: String,
        override val uri: URI?,
        val parameters: List<Parameter>,
        val returnType: ClassNode?,
        val node: MethodNode,
    ) : NodeReference()

    data class Field(
        override val name: String,
        override val uri: URI?,
        val owner: ClassNode?,
        val type: ClassNode?,
        val node: ASTNode,
    ) : NodeReference()

    data class Class(override val name: String, override val uri: URI?, val packageName: String?, val node: ClassNode) :
        NodeReference()

    data class Property(
        override val name: String,
        override val uri: URI?,
        val type: ClassNode?,
        val owner: ClassNode?,
        val node: PropertyNode,
    ) : NodeReference()
}

/**
 * Extension functions for type-safe AST node handling
 */

/**
 * Safely extracts position from an AST node
 */
fun ASTNode.safePosition(): LspResult<SafePosition> = SafePosition.fromAst(lineNumber, columnNumber)

/**
 * Safely extracts end position from an AST node
 */
fun ASTNode.safeEndPosition(): LspResult<SafePosition> = SafePosition.fromAst(lastLineNumber, lastColumnNumber)

/**
 * Creates a safe Range from an AST node
 */
fun ASTNode.safeRange(): LspResult<Range> = safePosition().flatMapResult { start: SafePosition ->
    safeEndPosition().map { end: SafePosition ->
        Range(start.toLspPosition(), end.toLspPosition())
    }
}

/**
 * Checks if an AST node has valid position information
 */
fun ASTNode.hasValidPosition(): Boolean =
    lineNumber > 0 && columnNumber > 0 && lastLineNumber > 0 && lastColumnNumber > 0

/**
 * Checks if a position is within an AST node's range
 */
fun ASTNode.containsPosition(position: Position): Boolean = safePosition().flatMapResult { start: SafePosition ->
    safeEndPosition().map { end: SafePosition ->
        SafePosition.fromLsp(position).isWithin(start, end)
    }
}.getOrDefault(false)

/**
 * Calculates the range size of a node for prioritization
 */
fun ASTNode.rangeSize(): Int = if (!hasValidPosition()) {
    Int.MAX_VALUE
} else {
    val lineSpan = lastLineNumber - lineNumber
    val columnSpan = if (lineSpan == 0) {
        lastColumnNumber - columnNumber
    } else {
        lineSpan * 100 + lastColumnNumber
    }
    lineSpan * 1000 + columnSpan
}

/**
 * Converts an AST node to a typed NodeReference
 */
fun ASTNode.toNodeReference(uri: URI? = null): NodeReference? = when (this) {
    is VariableExpression -> NodeReference.Variable(
        name = name,
        uri = uri,
        type = type,
        node = this,
    )

    is MethodNode -> NodeReference.Method(
        name = name,
        uri = uri,
        parameters = parameters.toList(),
        returnType = returnType,
        node = this,
    )

    is FieldNode -> NodeReference.Field(
        name = name,
        uri = uri,
        owner = declaringClass,
        type = type,
        node = this,
    )

    is PropertyNode -> NodeReference.Property(
        name = name,
        uri = uri,
        type = type,
        owner = declaringClass,
        node = this,
    )

    is ClassNode -> NodeReference.Class(
        name = nameWithoutPackage,
        uri = uri,
        packageName = packageName,
        node = this,
    )

    else -> null
}

/**
 * Checks if a node represents a referenceable symbol
 */
fun ASTNode.isReferenceableSymbol(): Boolean = when (this) {
    is VariableExpression -> true
    is MethodCallExpression -> true
    is MethodNode -> true
    is FieldNode -> true
    is PropertyNode -> true
    is Parameter -> true
    is ClassNode -> true
    is ConstructorCallExpression -> true
    is PropertyExpression -> true
    is ClassExpression -> true
    else -> false
}

/**
 * Checks if a node represents a reference (not a declaration)
 */
fun ASTNode.isReference(): Boolean = when (this) {
    is VariableExpression -> true
    is MethodCallExpression -> true
    is ConstructorCallExpression -> true
    is PropertyExpression -> true
    else -> false
}

/**
 * Checks if a node represents a declaration
 */
fun ASTNode.isDeclaration(): Boolean = when (this) {
    is MethodNode -> true
    is ClassNode -> true
    is FieldNode -> true
    is PropertyNode -> true
    is Parameter -> true
    else -> false
}

/**
 * Gets the simple name of a node type for debugging/logging
 */
fun ASTNode.typeName(): String = this::class.java.simpleName

/**
 * Safely gets the name of a node if it has one
 */
fun ASTNode.safeName(): String? = when (this) {
    is VariableExpression -> name
    is MethodNode -> name
    is FieldNode -> name
    is PropertyNode -> name
    is ClassNode -> nameWithoutPackage
    is Parameter -> name
    else -> null
}

/**
 * Extension function for creating validation errors
 */
fun ASTNode.validationError(message: String): LspError.InternalError = LspError.InternalError(
    operation = "node_validation",
    reason = "$message for ${typeName()} at line $lineNumber",
)
