package com.github.albertocavalcante.gvy.semantics.native

import com.github.albertocavalcante.gvy.semantics.SemanticType
import org.codehaus.groovy.ast.expr.ClosureListExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.DoWhileStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.ast.stmt.IfStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.ast.stmt.SwitchStatement
import org.codehaus.groovy.ast.stmt.SynchronizedStatement
import org.codehaus.groovy.ast.stmt.TryCatchStatement
import org.codehaus.groovy.ast.stmt.WhileStatement

/**
 * Information about a single variable declaration extracted from AST.
 *
 * @property name Variable name
 * @property inferredType Semantic type inferred from the declaration
 * @property line 1-based line number
 * @property column 1-based column number
 * @property mapKeys If RHS is a MapExpression, contains the extracted keys with their value types
 */
data class DeclarationInfo(
    val name: String,
    val inferredType: SemanticType,
    val line: Int,
    val column: Int,
    val mapKeys: List<MapKeyInfo>? = null,
)

/**
 * Information about a single key in a map literal.
 *
 * @property key The key string (from constant or bareword)
 * @property valueType Inferred type of the value expression
 */
data class MapKeyInfo(val key: String, val valueType: SemanticType)

/**
 * Result of walking a block for declarations.
 *
 * @property variables All variable declarations found in the block (including nested blocks)
 */
data class BlockDeclarations(val variables: List<DeclarationInfo>)

/**
 * Shared helper for walking BlockStatements and extracting variable declarations.
 *
 * This centralizes the "walk declarations in a block to find variables" logic that was
 * previously duplicated between:
 * - [GroovySemantics.populateScopeFromBlock]
 * - [SymbolExtractor.extractVariableSymbols]
 *
 * It also supports capturing map literal key metadata for completion features.
 */
object DeclarationWalker {

    /**
     * Walk a BlockStatement and extract all declarations with inferred types.
     *
     * @param block The block to walk
     * @param context Type context for inferring declaration types
     * @param captureMapKeys If true, extracts map literal keys when RHS is a MapExpression
     * @return All declarations found, with optional map key metadata
     */
    fun walk(block: BlockStatement, context: NativeTypeContext, captureMapKeys: Boolean = false): BlockDeclarations {
        val declarations = mutableListOf<DeclarationInfo>()
        walkBlock(block, context, captureMapKeys, declarations)
        return BlockDeclarations(declarations)
    }

    private fun walkBlock(
        block: BlockStatement,
        context: NativeTypeContext,
        captureMapKeys: Boolean,
        out: MutableList<DeclarationInfo>,
    ) {
        block.statements.forEach { walkStatement(it, context, captureMapKeys, out) }
    }

    /**
     * Walk a single statement, handling all statement types that can contain declarations.
     */
    private fun walkStatement(
        stmt: Statement,
        context: NativeTypeContext,
        captureMapKeys: Boolean,
        out: MutableList<DeclarationInfo>,
    ) {
        when (stmt) {
            is ExpressionStatement -> walkExpressionStatement(stmt, context, captureMapKeys, out)
            is BlockStatement -> walkBlock(stmt, context, captureMapKeys, out)
            is IfStatement -> walkIfStatement(stmt, context, captureMapKeys, out)
            is ForStatement -> walkForStatement(stmt, context, captureMapKeys, out)
            is WhileStatement -> walkChild(stmt.loopBlock, context, captureMapKeys, out)
            is DoWhileStatement -> walkChild(stmt.loopBlock, context, captureMapKeys, out)
            is TryCatchStatement -> walkTryCatchStatement(stmt, context, captureMapKeys, out)
            is SwitchStatement -> walkSwitchStatement(stmt, context, captureMapKeys, out)
            is SynchronizedStatement -> walkChild(stmt.code, context, captureMapKeys, out)
        }
    }

    private fun walkExpressionStatement(
        stmt: ExpressionStatement,
        context: NativeTypeContext,
        captureMapKeys: Boolean,
        out: MutableList<DeclarationInfo>,
    ) {
        (stmt.expression as? DeclarationExpression)?.let { decl ->
            out += extractDeclaration(decl, context, captureMapKeys)
        }
    }

    private fun walkIfStatement(
        stmt: IfStatement,
        context: NativeTypeContext,
        captureMapKeys: Boolean,
        out: MutableList<DeclarationInfo>,
    ) {
        walkChild(stmt.ifBlock, context, captureMapKeys, out)
        walkChild(stmt.elseBlock, context, captureMapKeys, out)
    }

    private fun walkForStatement(
        stmt: ForStatement,
        context: NativeTypeContext,
        captureMapKeys: Boolean,
        out: MutableList<DeclarationInfo>,
    ) {
        // Capture the loop variable declaration
        extractForLoopVariable(stmt, context, out)

        // Handle C-style for loops
        extractCStyleForDeclarations(stmt, context, captureMapKeys, out)

        walkChild(stmt.loopBlock, context, captureMapKeys, out)
    }

    private fun extractForLoopVariable(
        stmt: ForStatement,
        context: NativeTypeContext,
        out: MutableList<DeclarationInfo>,
    ) {
        val loopVar = stmt.variable
        // FOR_LOOP_DUMMY is a placeholder parameter when there's no loop variable
        if (loopVar != null && loopVar !== ForStatement.FOR_LOOP_DUMMY && loopVar.name != null) {
            val calculatedType = context.calculateType(loopVar)
            out += DeclarationInfo(
                name = loopVar.name,
                inferredType = calculatedType,
                line = loopVar.lineNumber,
                column = loopVar.columnNumber,
            )
        }
    }

    private fun extractCStyleForDeclarations(
        stmt: ForStatement,
        context: NativeTypeContext,
        captureMapKeys: Boolean,
        out: MutableList<DeclarationInfo>,
    ) {
        // The 'int i = 0' is hidden inside collectionExpression which is a ClosureListExpression
        val collectionExpr = stmt.collectionExpression
        if (collectionExpr is ClosureListExpression) {
            collectionExpr.expressions.forEach { expr ->
                if (expr is DeclarationExpression) {
                    out += extractDeclaration(expr, context, captureMapKeys)
                }
            }
        }
    }

    private fun walkTryCatchStatement(
        stmt: TryCatchStatement,
        context: NativeTypeContext,
        captureMapKeys: Boolean,
        out: MutableList<DeclarationInfo>,
    ) {
        walkChild(stmt.tryStatement, context, captureMapKeys, out)
        stmt.catchStatements.forEach { catchStmt ->
            extractCatchVariable(catchStmt, context, out)
            walkChild(catchStmt.code, context, captureMapKeys, out)
        }
        stmt.finallyStatement?.let { walkChild(it, context, captureMapKeys, out) }
    }

    private fun extractCatchVariable(
        catchStmt: CatchStatement,
        context: NativeTypeContext,
        out: MutableList<DeclarationInfo>,
    ) {
        val catchVar = catchStmt.variable ?: return
        val calculatedType = context.calculateType(catchVar)
        val varType = calculatedType as? SemanticType
            ?: SemanticType.Unknown("catch variable type inference")
        out += DeclarationInfo(
            name = catchVar.name,
            inferredType = varType,
            line = catchVar.lineNumber,
            column = catchVar.columnNumber,
        )
    }

    private fun walkSwitchStatement(
        stmt: SwitchStatement,
        context: NativeTypeContext,
        captureMapKeys: Boolean,
        out: MutableList<DeclarationInfo>,
    ) {
        stmt.caseStatements.forEach { walkChild(it.code, context, captureMapKeys, out) }
        stmt.defaultStatement?.let { walkChild(it, context, captureMapKeys, out) }
    }

    /**
     * Walk a child statement (may be a block or single statement).
     */
    private fun walkChild(
        stmt: Statement?,
        context: NativeTypeContext,
        captureMapKeys: Boolean,
        out: MutableList<DeclarationInfo>,
    ) {
        stmt ?: return
        when (stmt) {
            is BlockStatement -> walkBlock(stmt, context, captureMapKeys, out)
            else -> walkStatement(stmt, context, captureMapKeys, out)
        }
    }

    private fun extractDeclaration(
        decl: DeclarationExpression,
        context: NativeTypeContext,
        captureMapKeys: Boolean,
    ): DeclarationInfo {
        val mapKeys = if (captureMapKeys) {
            (decl.rightExpression as? MapExpression)?.let { extractMapKeys(it, context) }
        } else {
            null
        }

        return DeclarationInfo(
            name = decl.variableExpression.name,
            inferredType = context.calculateType(decl),
            line = decl.lineNumber,
            column = decl.columnNumber,
            mapKeys = mapKeys,
        )
    }

    private fun extractMapKeys(mapExpr: MapExpression, context: NativeTypeContext): List<MapKeyInfo> =
        mapExpr.mapEntryExpressions.mapNotNull { entry ->
            val key = when (val keyExpr = entry.keyExpression) {
                is ConstantExpression -> keyExpr.value?.toString()
                is VariableExpression -> keyExpr.name // Bareword key like [foo: 1]
                else -> null
            }
            key?.let { MapKeyInfo(it, context.calculateType(entry.valueExpression)) }
        }
}
