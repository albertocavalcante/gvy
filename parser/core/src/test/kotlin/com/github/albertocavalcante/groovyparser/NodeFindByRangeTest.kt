package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ExpressionStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ReturnStatement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NodeFindByRangeTest {

    @Test
    fun `range contains another range when both positions are within bounds`() {
        val outerRange = Range(Position(1, 1), Position(10, 1))
        val innerRange = Range(Position(2, 5), Position(5, 10))

        assertThat(outerRange.contains(innerRange)).isTrue()
    }

    @Test
    fun `range does not contain another range when begin is outside`() {
        val range1 = Range(Position(2, 1), Position(10, 1))
        val range2 = Range(Position(1, 1), Position(5, 1))

        assertThat(range1.contains(range2)).isFalse()
    }

    @Test
    fun `range does not contain another range when end is outside`() {
        val range1 = Range(Position(1, 1), Position(5, 1))
        val range2 = Range(Position(2, 1), Position(10, 1))

        assertThat(range1.contains(range2)).isFalse()
    }

    @Test
    fun `findByRange returns empty when node has no range`() {
        val node = CompilationUnit()
        val targetRange = Range(Position(1, 1), Position(1, 1))

        val result = node.findByRange(targetRange)

        assertThat(result).isEmpty()
    }

    @Test
    fun `findByRange returns empty when target range is outside node range`() {
        val node = CompilationUnit()
        node.range = Range(Position(1, 1), Position(5, 1))
        val targetRange = Range(Position(10, 1), Position(10, 1))

        val result = node.findByRange(targetRange)

        assertThat(result).isEmpty()
    }

    @Test
    fun `findByRange returns node when target range is within node range and no children match`() {
        val node = CompilationUnit()
        node.range = Range(Position(1, 1), Position(10, 1))
        val targetRange = Range(Position(5, 1), Position(5, 1))

        val result = node.findByRange(targetRange)

        assertThat(result).isPresent
        assertThat(result.get()).isSameAs(node)
    }

    @Test
    fun `findByRange returns deepest child that contains target range`() {
        // Create a tree: CompilationUnit > ClassDeclaration > MethodDeclaration
        val unit = CompilationUnit()
        unit.range = Range(Position(1, 1), Position(20, 1))

        val classDecl = ClassDeclaration("TestClass")
        classDecl.range = Range(Position(1, 1), Position(20, 1))
        unit.addType(classDecl)

        val methodDecl = MethodDeclaration("testMethod", "void")
        methodDecl.range = Range(Position(5, 5), Position(10, 10))
        classDecl.addMethod(methodDecl)

        // Target a position inside the method
        val targetRange = Range(Position(7, 7), Position(7, 7))

        val result = unit.findByRange(targetRange)

        assertThat(result).isPresent
        assertThat(result.get()).isSameAs(methodDecl)
        assertThat(result.get()).isNotSameAs(classDecl)
        assertThat(result.get()).isNotSameAs(unit)
    }

    @Test
    fun `findByRange with method call expression`() {
        // Create: MethodDeclaration containing a MethodCallExpr
        val unit = CompilationUnit()
        unit.range = Range(Position(1, 1), Position(20, 1))

        val classDecl = ClassDeclaration("TestClass")
        classDecl.range = Range(Position(1, 1), Position(20, 1))
        unit.addType(classDecl)

        val methodDecl = MethodDeclaration("testMethod", "void")
        methodDecl.range = Range(Position(2, 5), Position(10, 10))
        classDecl.addMethod(methodDecl)

        val blockStmt = BlockStatement()
        blockStmt.range = Range(Position(3, 5), Position(9, 5))
        methodDecl.body = blockStmt

        val methodCall = MethodCallExpr(null, "println")
        methodCall.range = Range(Position(4, 9), Position(4, 25))
        val exprStmt = ExpressionStatement(methodCall)
        exprStmt.range = Range(Position(4, 9), Position(4, 25))
        blockStmt.addStatement(exprStmt)

        // Target the method call position
        val targetRange = Range(Position(4, 15), Position(4, 15))

        val result = unit.findByRange(targetRange)

        assertThat(result).isPresent
        assertThat(result.get()).isSameAs(methodCall)
    }

    @Test
    fun `findByRange with variable expression`() {
        // Create: ReturnStatement containing a VariableExpr
        val unit = CompilationUnit()
        unit.range = Range(Position(1, 1), Position(20, 1))

        val classDecl = ClassDeclaration("TestClass")
        classDecl.range = Range(Position(1, 1), Position(20, 1))
        unit.addType(classDecl)

        val methodDecl = MethodDeclaration("getValue", "int")
        methodDecl.range = Range(Position(2, 5), Position(10, 10))
        classDecl.addMethod(methodDecl)

        val blockStmt = BlockStatement()
        blockStmt.range = Range(Position(3, 5), Position(9, 5))
        methodDecl.body = blockStmt

        val variableExpr = VariableExpr("value")
        variableExpr.range = Range(Position(4, 16), Position(4, 21))
        val returnStmt = ReturnStatement(variableExpr)
        returnStmt.range = Range(Position(4, 9), Position(4, 21))
        blockStmt.addStatement(returnStmt)

        // Target the variable position
        val targetRange = Range(Position(4, 18), Position(4, 18))

        val result = unit.findByRange(targetRange)

        assertThat(result).isPresent
        assertThat(result.get()).isSameAs(variableExpr)
    }

    @Test
    fun `findByRange handles nested expressions and returns innermost`() {
        // Create nested structure where outer expression contains inner expression
        val unit = CompilationUnit()
        unit.range = Range(Position(1, 1), Position(20, 1))

        val classDecl = ClassDeclaration("TestClass")
        classDecl.range = Range(Position(1, 1), Position(20, 1))
        unit.addType(classDecl)

        val methodDecl = MethodDeclaration("test", "void")
        methodDecl.range = Range(Position(2, 5), Position(10, 10))
        classDecl.addMethod(methodDecl)

        val blockStmt = BlockStatement()
        blockStmt.range = Range(Position(3, 5), Position(9, 5))
        methodDecl.body = blockStmt

        // Outer method call: obj.method(arg)
        val outerCall = MethodCallExpr(null, "method")
        outerCall.range = Range(Position(4, 9), Position(4, 30))

        // Inner variable expression: arg
        val innerVar = VariableExpr("arg")
        innerVar.range = Range(Position(4, 23), Position(4, 26))
        outerCall.addArgument(innerVar)

        val exprStmt = ExpressionStatement(outerCall)
        exprStmt.range = Range(Position(4, 9), Position(4, 30))
        blockStmt.addStatement(exprStmt)

        // Target position inside the argument
        val targetRange = Range(Position(4, 24), Position(4, 24))

        val result = unit.findByRange(targetRange)

        assertThat(result).isPresent
        assertThat(result.get()).isSameAs(innerVar)
        assertThat(result.get()).isNotSameAs(outerCall)
    }

    @Test
    fun `findByRange at boundary position returns containing node`() {
        val unit = CompilationUnit()
        unit.range = Range(Position(1, 1), Position(20, 1))

        val classDecl = ClassDeclaration("TestClass")
        classDecl.range = Range(Position(2, 1), Position(10, 1))
        unit.addType(classDecl)

        // Target at exact begin position
        val targetRange = Range(Position(2, 1), Position(2, 1))

        val result = unit.findByRange(targetRange)

        assertThat(result).isPresent
        assertThat(result.get()).isSameAs(classDecl)
    }

    @Test
    fun `findByRange when multiple children exist but only one contains target`() {
        val unit = CompilationUnit()
        unit.range = Range(Position(1, 1), Position(50, 1))

        val classDecl = ClassDeclaration("TestClass")
        classDecl.range = Range(Position(1, 1), Position(50, 1))
        unit.addType(classDecl)

        val method1 = MethodDeclaration("method1", "void")
        method1.range = Range(Position(2, 5), Position(10, 10))
        classDecl.addMethod(method1)

        val method2 = MethodDeclaration("method2", "void")
        method2.range = Range(Position(15, 5), Position(25, 10))
        classDecl.addMethod(method2)

        val method3 = MethodDeclaration("method3", "void")
        method3.range = Range(Position(30, 5), Position(40, 10))
        classDecl.addMethod(method3)

        // Target position in method2
        val targetRange = Range(Position(20, 7), Position(20, 7))

        val result = unit.findByRange(targetRange)

        assertThat(result).isPresent
        assertThat(result.get()).isSameAs(method2)
    }

    @Test
    fun `findByRange with single point range at exact position`() {
        val node = CompilationUnit()
        node.range = Range(Position(1, 1), Position(10, 10))

        val targetRange = Range(Position(5, 5), Position(5, 5))

        val result = node.findByRange(targetRange)

        assertThat(result).isPresent
        assertThat(result.get()).isSameAs(node)
    }
}
