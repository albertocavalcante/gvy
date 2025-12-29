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
import com.github.albertocavalcante.groovyparser.ast.expr.ArrayExpr
import com.github.albertocavalcante.groovyparser.ast.expr.AttributeExpr
import com.github.albertocavalcante.groovyparser.ast.expr.BinaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.BitwiseNegationExpr
import com.github.albertocavalcante.groovyparser.ast.expr.CastExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ClassExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ClosureExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstantExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstructorCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.DeclarationExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ElvisExpr
import com.github.albertocavalcante.groovyparser.ast.expr.GStringExpr
import com.github.albertocavalcante.groovyparser.ast.expr.LambdaExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ListExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MapEntryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MapExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodPointerExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodReferenceExpr
import com.github.albertocavalcante.groovyparser.ast.expr.NotExpr
import com.github.albertocavalcante.groovyparser.ast.expr.PostfixExpr
import com.github.albertocavalcante.groovyparser.ast.expr.PrefixExpr
import com.github.albertocavalcante.groovyparser.ast.expr.PropertyExpr
import com.github.albertocavalcante.groovyparser.ast.expr.RangeExpr
import com.github.albertocavalcante.groovyparser.ast.expr.SpreadExpr
import com.github.albertocavalcante.groovyparser.ast.expr.SpreadMapExpr
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
 * A visitor that does not return anything.
 *
 * Similar to JavaParser's VoidVisitor, this allows type-safe AST traversal
 * for side-effect operations like collecting data or modifying state.
 *
 * Example usage:
 * ```kotlin
 * class MethodCollector : VoidVisitor<MutableList<String>> {
 *     override fun visit(n: MethodDeclaration, arg: MutableList<String>) {
 *         arg.add(n.name)
 *     }
 *     // ... other visit methods
 * }
 *
 * val methods = mutableListOf<String>()
 * compilationUnit.accept(MethodCollector(), methods)
 * ```
 *
 * @param A the type of the argument passed to visit methods
 */
interface VoidVisitor<A> {

    // Top-level
    fun visit(n: CompilationUnit, arg: A)
    fun visit(n: PackageDeclaration, arg: A)
    fun visit(n: ImportDeclaration, arg: A)
    fun visit(n: AnnotationExpr, arg: A)

    // Body declarations
    fun visit(n: ClassDeclaration, arg: A)
    fun visit(n: MethodDeclaration, arg: A)
    fun visit(n: FieldDeclaration, arg: A)
    fun visit(n: ConstructorDeclaration, arg: A)
    fun visit(n: Parameter, arg: A)

    // Statements
    fun visit(n: BlockStatement, arg: A)
    fun visit(n: ExpressionStatement, arg: A)
    fun visit(n: IfStatement, arg: A)
    fun visit(n: ForStatement, arg: A)
    fun visit(n: WhileStatement, arg: A)
    fun visit(n: ReturnStatement, arg: A)
    fun visit(n: TryCatchStatement, arg: A)
    fun visit(n: CatchClause, arg: A)
    fun visit(n: SwitchStatement, arg: A)
    fun visit(n: CaseStatement, arg: A)
    fun visit(n: ThrowStatement, arg: A)
    fun visit(n: AssertStatement, arg: A)
    fun visit(n: BreakStatement, arg: A)
    fun visit(n: ContinueStatement, arg: A)

    // Expressions
    fun visit(n: MethodCallExpr, arg: A)
    fun visit(n: VariableExpr, arg: A)
    fun visit(n: ConstantExpr, arg: A)
    fun visit(n: BinaryExpr, arg: A)
    fun visit(n: PropertyExpr, arg: A)
    fun visit(n: ClosureExpr, arg: A)
    fun visit(n: GStringExpr, arg: A)
    fun visit(n: ListExpr, arg: A)
    fun visit(n: MapExpr, arg: A)
    fun visit(n: MapEntryExpr, arg: A)
    fun visit(n: RangeExpr, arg: A)
    fun visit(n: TernaryExpr, arg: A)
    fun visit(n: UnaryExpr, arg: A)
    fun visit(n: CastExpr, arg: A)
    fun visit(n: ConstructorCallExpr, arg: A)

    // New expressions (Groovy-specific)
    fun visit(n: ElvisExpr, arg: A)
    fun visit(n: SpreadExpr, arg: A)
    fun visit(n: SpreadMapExpr, arg: A)
    fun visit(n: AttributeExpr, arg: A)
    fun visit(n: BitwiseNegationExpr, arg: A)
    fun visit(n: NotExpr, arg: A)
    fun visit(n: PostfixExpr, arg: A)
    fun visit(n: PrefixExpr, arg: A)
    fun visit(n: MethodPointerExpr, arg: A)
    fun visit(n: MethodReferenceExpr, arg: A)
    fun visit(n: LambdaExpr, arg: A)
    fun visit(n: DeclarationExpr, arg: A)
    fun visit(n: ClassExpr, arg: A)
    fun visit(n: ArrayExpr, arg: A)
}
