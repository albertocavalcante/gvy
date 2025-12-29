package com.github.albertocavalcante.groovyparser.ast.visitor

import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.ImportDeclaration
import com.github.albertocavalcante.groovyparser.ast.PackageDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ConstructorDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.groovyparser.ast.expr.BinaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ClosureExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstantExpr
import com.github.albertocavalcante.groovyparser.ast.expr.GStringExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.PropertyExpr
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ExpressionStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ForStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.IfStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ReturnStatement
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

    // Expressions
    fun visit(n: MethodCallExpr, arg: A)
    fun visit(n: VariableExpr, arg: A)
    fun visit(n: ConstantExpr, arg: A)
    fun visit(n: BinaryExpr, arg: A)
    fun visit(n: PropertyExpr, arg: A)
    fun visit(n: ClosureExpr, arg: A)
    fun visit(n: GStringExpr, arg: A)
}
