package com.github.albertocavalcante.groovylsp.providers.rename

import com.github.albertocavalcante.groovylsp.TestUtils
import com.github.albertocavalcante.groovylsp.ast.resolveToDefinition
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Debug test to understand why rename fails for local variables.
 * This will help us understand the AST structure and symbol table contents.
 */
class DebugRenameTest {

    private lateinit var compilationService: com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService

    @BeforeEach
    fun setup() {
        compilationService = TestUtils.createCompilationService()
    }

    @Test
    fun `debug - examine local variable AST structure and accessedVariable`() = runBlocking {
        val code = """
            def props = obj.properties.clone()
            println props
            return props
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        println("=== DEBUGGING RENAME ISSUE ===")
        println("Code to analyze:")
        println(code)
        println()

        // Step 1: Compile the code
        println("Step 1: Compiling...")
        val compilationResult = compilationService.compile(uri, code)
        println("Compilation success: ${compilationResult.isSuccess}")
        println("AST available: ${compilationResult.ast != null}")
        println()

        // Step 2: Get AST visitor and symbol table
        println("Step 2: Getting AST visitor and symbol table...")
        val astVisitor = compilationService.getAstVisitor(uri)
        val symbolTable = compilationService.getSymbolTable(uri)
        println("AST visitor available: ${astVisitor != null}")
        println("Symbol table available: ${symbolTable != null}")

        if (astVisitor != null && symbolTable != null) {
            println("Total nodes in AST: ${astVisitor.getAllNodes().size}")
            println("Symbol table statistics: ${symbolTable.getStatistics()}")
            println()

            // Step 3: Find the variable at position
            println("Step 3: Finding node at position (0, 6) - inside 'props'...")
            val position = Position(0, 6)
            val node = astVisitor.getNodeAt(uri, position.line, position.character)
            println("Found node: ${node?.javaClass?.simpleName}")

            if (node is VariableExpression) {
                println("Variable name: ${node.name}")
                println("Variable type: ${node.type}")
                println("AccessedVariable: ${node.accessedVariable}")
                println("AccessedVariable class: ${node.accessedVariable?.javaClass?.simpleName}")
                println("AccessedVariable is ASTNode: ${node.accessedVariable is org.codehaus.groovy.ast.ASTNode}")
                println()

                // Step 4: Check symbol table contents
                println("Step 4: Checking symbol table for variable '${node.name}'...")
                val foundInSymbolTable = symbolTable.registry.findVariableDeclaration(uri, node.name)
                println("Found in symbol table: $foundInSymbolTable")
                println("Symbol table variable type: ${foundInSymbolTable?.javaClass?.simpleName}")
                println()

                // Step 5: Test resolveToDefinition
                println("Step 5: Testing resolveToDefinition...")
                val definition = node.resolveToDefinition(astVisitor, symbolTable, strict = false)
                println("Definition returned: $definition")
                println("Definition class: ${definition?.javaClass?.simpleName}")
                println()

                // Step 6: Check all variable expressions in AST
                println("Step 6: All VariableExpression nodes with name '${node.name}':")
                val allNodes = astVisitor.getAllNodes()
                val variableNodes = allNodes.filterIsInstance<VariableExpression>()
                    .filter { it.name == node.name }

                variableNodes.forEachIndexed { index, varNode ->
                    println("  [$index] VariableExpression at ${varNode.lineNumber}:${varNode.columnNumber}")
                    println("       accessedVariable: ${varNode.accessedVariable}")
                    println(
                        "       accessedVariable == node.accessedVariable: " +
                            "${varNode.accessedVariable == node.accessedVariable}",
                    )
                    println("       same object identity: ${varNode === node}")
                }
            } else {
                println("ERROR: Node at position is not a VariableExpression!")
                println("Found nodes near position:")
                astVisitor.getAllNodes()
                    .filter { it.lineNumber >= 0 && it.lineNumber <= 2 }
                    .forEach { nearbyNode ->
                        println(
                            "  ${nearbyNode.javaClass.simpleName} at " +
                                "${nearbyNode.lineNumber}:${nearbyNode.columnNumber}",
                        )
                    }
            }
        } else {
            println("ERROR: Could not get AST visitor or symbol table!")
        }

        println("=== END DEBUG ===")
    }
}
