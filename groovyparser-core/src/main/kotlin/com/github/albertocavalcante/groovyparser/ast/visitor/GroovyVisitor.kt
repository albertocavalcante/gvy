package com.github.albertocavalcante.groovyparser.ast.visitor

import com.github.albertocavalcante.groovyparser.ast.AnnotationExpr
import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.ImportDeclaration
import com.github.albertocavalcante.groovyparser.ast.PackageDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ConstructorDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.groovyparser.ast.expr.BinaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.CastExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ClosureExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstantExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstructorCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.GStringExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ListExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MapEntryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MapExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.PropertyExpr
import com.github.albertocavalcante.groovyparser.ast.expr.RangeExpr
import com.github.albertocavalcante.groovyparser.ast.expr.TernaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.UnaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr
import com.github.albertocavalcante.groovyparser.ast.stmt.AssertStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.BreakStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.CaseStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.CatchClause
import com.github.albertocavalcante.groovyparser.ast.stmt.ContinueStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ExpressionStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ForStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.IfStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ReturnStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.SwitchStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ThrowStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.TryCatchStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.WhileStatement

/**
 * A visitor that returns a value for each node visited.
 *
 * Similar to JavaParser's GenericVisitor, this allows type-safe AST traversal
 * with a result type R and an argument type A.
 *
 * Example usage:
 * ```kotlin
 * class MethodCounter : GroovyVisitor<Int, Unit> {
 *     override fun visit(n: ClassDeclaration, arg: Unit): Int {
 *         return n.methods.size + n.methods.sumOf { visit(it, arg) }
 *     }
 *     // ... other visit methods
 * }
 * ```
 *
 * @param R the return type of the visit methods
 * @param A the type of the argument passed to visit methods
 */
interface GroovyVisitor<R, A> {

    // Top-level
    fun visit(n: CompilationUnit, arg: A): R
    fun visit(n: PackageDeclaration, arg: A): R
    fun visit(n: ImportDeclaration, arg: A): R
    fun visit(n: AnnotationExpr, arg: A): R

    // Body declarations
    fun visit(n: ClassDeclaration, arg: A): R
    fun visit(n: MethodDeclaration, arg: A): R
    fun visit(n: FieldDeclaration, arg: A): R
    fun visit(n: ConstructorDeclaration, arg: A): R
    fun visit(n: Parameter, arg: A): R

    // Statements
    fun visit(n: BlockStatement, arg: A): R
    fun visit(n: ExpressionStatement, arg: A): R
    fun visit(n: IfStatement, arg: A): R
    fun visit(n: ForStatement, arg: A): R
    fun visit(n: WhileStatement, arg: A): R
    fun visit(n: ReturnStatement, arg: A): R
    fun visit(n: TryCatchStatement, arg: A): R
    fun visit(n: CatchClause, arg: A): R
    fun visit(n: SwitchStatement, arg: A): R
    fun visit(n: CaseStatement, arg: A): R
    fun visit(n: ThrowStatement, arg: A): R
    fun visit(n: AssertStatement, arg: A): R
    fun visit(n: BreakStatement, arg: A): R
    fun visit(n: ContinueStatement, arg: A): R

    // Expressions
    fun visit(n: MethodCallExpr, arg: A): R
    fun visit(n: VariableExpr, arg: A): R
    fun visit(n: ConstantExpr, arg: A): R
    fun visit(n: BinaryExpr, arg: A): R
    fun visit(n: PropertyExpr, arg: A): R
    fun visit(n: ClosureExpr, arg: A): R
    fun visit(n: GStringExpr, arg: A): R
    fun visit(n: ListExpr, arg: A): R
    fun visit(n: MapExpr, arg: A): R
    fun visit(n: MapEntryExpr, arg: A): R
    fun visit(n: RangeExpr, arg: A): R
    fun visit(n: TernaryExpr, arg: A): R
    fun visit(n: UnaryExpr, arg: A): R
    fun visit(n: CastExpr, arg: A): R
    fun visit(n: ConstructorCallExpr, arg: A): R
}
