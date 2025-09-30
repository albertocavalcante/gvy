package com.github.albertocavalcante.groovylsp.providers.rename

import com.github.albertocavalcante.groovylsp.TestUtils
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.URI

class SimpleDebugTest {

    @Test
    fun `debug rename issue`() = runBlocking {
        val compilationService = TestUtils.createCompilationService()
        val code = "def props = obj.properties.clone()"
        val uri = URI.create("file:///test.groovy")

        println("=== DEBUGGING ===")
        val result = compilationService.compile(uri, code)
        println("Compilation success: ${result.isSuccess}")

        val astVisitor = compilationService.getAstVisitor(uri)
        val symbolTable = compilationService.getSymbolTable(uri)
        println("AST visitor: ${astVisitor != null}")
        println("Symbol table: ${symbolTable != null}")

        if (astVisitor != null) {
            val node = astVisitor.getNodeAt(uri, 0, 6) // Inside "props"
            println("Found node: ${node?.javaClass?.simpleName}")

            if (node is org.codehaus.groovy.ast.expr.VariableExpression) {
                println("Variable name: ${node.name}")
                println("AccessedVariable: ${node.accessedVariable}")

                if (symbolTable != null) {
                    val fromTable = symbolTable.registry.findVariableDeclaration(uri, node.name)
                    println("From symbol table: $fromTable")
                }
            }
        }
        println("=== END ===")
    }
}
