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
 * TODO: ARCHITECTURAL CONSOLIDATION REQUIRED
 *
 * This file contains a sophisticated coordinate system using:
 * - Value classes (LineNumber, ColumnNumber) for compile-time type safety
 * - Result monad (LspResult) for comprehensive error handling
 * - SafePosition for validated coordinate operations
 *
 * CONFLICT: We now have CoordinateSystem as the "single source of truth"
 * but this SafePosition approach is actually MORE sophisticated!
 *
 * FUTURE CONSOLIDATION PLAN:
 * Phase 1 (NOW): Make SafePosition delegate to CoordinateSystem for consistency
 * Phase 2: Merge SafePosition's advanced features INTO CoordinateSystem:
 *    - Move LineNumber/ColumnNumber value classes to CoordinateSystem
 *    - Adopt Result<T> pattern in CoordinateSystem for all operations
 *    - Provide both simple APIs (current) and safe APIs (Result-based)
 * Phase 3: Deprecate this parallel implementation
 * Phase 4: Keep extension functions but use CoordinateSystem types internally
 *
 * Benefits of merger:
 * - Single source of truth for coordinate logic
 * - Type safety from value classes
 * - Error handling from Result pattern
 * - Backwards compatibility through extension functions
 *
 * See CoordinateSystem.kt for the migration tracking.
 */

// Constants for range size calculations
private const val COLUMN_WEIGHT_FOR_MULTILINE = 100
private const val LINE_WEIGHT = 1000

/**
 * Type-safe wrappers for position coordinates
 *
 * TODO: MIGRATE TO CoordinateSystem
 * This value class provides compile-time type safety that prevents:
 * - Mixing line numbers with column numbers
 * - Passing arbitrary integers where lines are expected
 * - Forgetting to validate negative numbers
 *
 * Migration strategy:
 * 1. Copy this to CoordinateSystem.LineNumber with identical API
 * 2. Add typealiases here: typealias LineNumber = CoordinateSystem.LineNumber
 * 3. Remove typealiases after all code updated
 * 4. This approach prevents ANY coordinate type confusion at compile time!
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

/**
 * TODO: MIGRATE TO CoordinateSystem
 * Same benefits as LineNumber - prevents column/line confusion at compile time.
 * The 'value class' (inline class) has ZERO runtime overhead but provides
 * complete type safety. This pattern should be adopted throughout the LSP.
 */
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
 *
 * TODO: MERGE INTO CoordinateSystem as SafeLspPosition/SafeGroovyPosition
 *
 * Key innovations to preserve:
 * 1. Value class wrapping - zero runtime cost, full compile-time safety
 * 2. Factory methods return Result<SafePosition> for safe construction
 * 3. isWithin() method for elegant range checking
 * 4. Companion object pattern for static factories
 *
 * The merged implementation should offer both APIs:
 * - Simple: CoordinateSystem.nodeContainsPosition(node, line, char): Boolean
 * - Safe: CoordinateSystem.safeContainsPosition(node, pos): Result<Boolean>
 *
 * This allows gradual migration while improving safety.
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
 * Checks if a position is within an AST node's range
 * This overload takes a Position object and delegates to CoordinateSystem for consistency.
 */
fun ASTNode.containsPosition(position: Position): Boolean {
    // Delegate to CoordinateSystem for consistent position checking
    return CoordinateSystem.nodeContainsPosition(this, position)
}

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
