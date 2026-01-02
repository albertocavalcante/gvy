package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.api.ParseRequest
import com.github.albertocavalcante.groovyparser.ast.SymbolExtractor
import com.github.albertocavalcante.groovyparser.ast.toHoverString
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.stmt.SwitchStatement
import org.codehaus.groovy.ast.stmt.TryCatchStatement
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KitchenSinkCoverageTest {

    // Use the test's classloader to ensure Groovy classes (like @CompileStatic) are available
    private val parser = GroovyParserFacade(GroovyParserFacade::class.java.classLoader)

    @Test
    fun `parse and visit kitchen sink file covers many node types`() {
        // Load the kitchen sink file
        // Try relative path first (standard gradle project layout)
        var file = File("groovy-parser/src/test/resources/kitchen-sink.groovy")
        if (!file.exists()) {
            // Try relative to the module root (if running from module dir)
            file = File("src/test/resources/kitchen-sink.groovy")
        }

        assertTrue(file.exists(), "Kitchen sink file not found at ${file.absolutePath}")

        val content = file.readText()
        val uri = URI.create("file:///kitchen-sink.groovy")

        val result = parser.parse(
            ParseRequest(
                uri = uri,
                content = content,
            ),
        )

        assertTrue(result.isSuccessful, "Parsing failed with: ${result.diagnostics}")
        assertNotNull(result.ast, "AST should not be null")

        // 1. Inspect AST model
        val astModel = result.astModel
        val allNodes = astModel.getAllNodes()
        val classNodes = astModel.getAllClassNodes()

        // Assert we found the classes
        val mainClass =
            assertNotNull(classNodes.find { it.name == "com.example.KitchenSink" }, "Should find KitchenSink class")

        assertNotNull(
            classNodes.find { it.name == "com.example.KitchenSink\$Inner" },
            "Should find Inner class",
        )

        // Assert we found specific complex nodes
        assertNotNull(
            allNodes.filterIsInstance<TryCatchStatement>().firstOrNull(),
            "Should find TryCatchStatement",
        )

        assertNotNull(
            allNodes.filterIsInstance<GStringExpression>().firstOrNull(),
            "Should find GStringExpression",
        )

        // Check for the SwitchStatement (Testing coverage/bug)
        assertNotNull(
            allNodes.filterIsInstance<SwitchStatement>().firstOrNull(),
            "Should find SwitchStatement (Testing coverage/bug)",
        )

        // Check for CaseStatement (also likely missing)
        assertNotNull(
            allNodes.filterIsInstance<org.codehaus.groovy.ast.stmt.CaseStatement>().firstOrNull(),
            "Should find CaseStatement",
        )

        // 2. Extract Symbols (Testing SymbolExtractor)
        val classSymbols = SymbolExtractor.extractClassSymbols(result.ast!!)
        assertTrue(classSymbols.isNotEmpty(), "Should extract class symbols")

        val methodSymbols = SymbolExtractor.extractMethodSymbols(mainClass)
        assertTrue(methodSymbols.isNotEmpty(), "Should extract method symbols")
        assertTrue(methodSymbols.any { it.name == "doSomething" }, "Should find doSomething method")

        val fieldSymbols = SymbolExtractor.extractFieldSymbols(mainClass)
        assertTrue(fieldSymbols.isNotEmpty(), "Should extract field symbols")
        assertTrue(fieldSymbols.any { it.name == "secret" }, "Should find secret field")

        val completionSymbols = SymbolExtractor.extractCompletionSymbols(result.ast!!, 10, 0)
        assertNotNull(completionSymbols, "Should get completion context")

        // 3. Test Node Formatting (Testing NodeFormatter)
        val classHover = mainClass.toHoverString()
        assertTrue(classHover.contains("class KitchenSink"), "Class hover should contain name")
        assertTrue(classHover.contains("implements Serializable"), "Class hover should show interfaces")

        val methodNode = mainClass.methods.find { it.name == "doSomething" }!!
        val methodHover = methodNode.toHoverString()
        assertTrue(methodHover.contains("doSomething"), "Method hover should contain name")
        assertTrue(methodHover.contains("String"), "Method hover should show types")

        val fieldNode = mainClass.fields.find { it.name == "secret" }!!
        val fieldHover = fieldNode.toHoverString()
        assertTrue(fieldHover.contains("secret"), "Field hover should contain name")
    }
}
