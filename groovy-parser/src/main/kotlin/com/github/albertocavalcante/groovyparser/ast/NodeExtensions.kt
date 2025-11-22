package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.ast.types.Position
import com.github.albertocavalcante.groovyparser.ast.types.Range
import com.github.albertocavalcante.groovyparser.errors.GroovyParserError
import com.github.albertocavalcante.groovyparser.errors.GroovyParserResult
import com.github.albertocavalcante.groovyparser.errors.flatMapResult
import com.github.albertocavalcante.groovyparser.errors.toGroovyParserResult
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
import java.net.URI

// Constants for range size calculations
private const val COLUMN_WEIGHT_FOR_MULTILINE = 100
private const val LINE_WEIGHT = 1000

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
    fun toParserPosition(): Position = Position(line.value, column.value)

    fun isWithin(start: SafePosition, end: SafePosition): Boolean = when {
        line.value < start.line.value -> false
        line.value > end.line.value -> false
        line.value == start.line.value && column.value < start.column.value -> false
        line.value == end.line.value && column.value > end.column.value -> false
        else -> true
    }

    companion object {
        fun fromGroovyPosition(position: Position): SafePosition = SafePosition(
            LineNumber.fromLsp(position.line),
            ColumnNumber.fromLsp(position.character),
        )

        fun fromAst(line: Int, column: Int): GroovyParserResult<SafePosition> = if (line > 0 && column > 0) {
            SafePosition(
                LineNumber.fromAst(line),
                ColumnNumber.fromAst(column),
            ).toGroovyParserResult()
        } else {
            GroovyParserError.InvalidPosition(
                URI.create("unknown"),
                line,
                column,
                "Invalid AST coordinates",
            ).toGroovyParserResult()
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
fun ASTNode.safePosition(): GroovyParserResult<SafePosition> = SafePosition.fromAst(lineNumber, columnNumber)

/**
 * Safely extracts end position from an AST node
 */
fun ASTNode.safeEndPosition(): GroovyParserResult<SafePosition> = SafePosition.fromAst(lastLineNumber, lastColumnNumber)

/**
 * Creates a safe Range from an AST node
 */
fun ASTNode.safeRange(): GroovyParserResult<Range> = safePosition().flatMapResult { start: SafePosition ->
    safeEndPosition().map { end: SafePosition ->
        Range(start.toParserPosition(), end.toParserPosition())
    }
}

/**
 * Checks if a position is within an AST node's range
 */
fun ASTNode.containsPosition(position: Position): Boolean = safePosition().flatMapResult { start: SafePosition ->
    safeEndPosition().map { end: SafePosition ->
        SafePosition.fromGroovyPosition(position).isWithin(start, end)
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
        lineSpan * COLUMN_WEIGHT_FOR_MULTILINE + lastColumnNumber
    }
    lineSpan * LINE_WEIGHT + columnSpan
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
