package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.ast.toHoverString
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.stmt.CaseStatement
import org.codehaus.groovy.ast.stmt.SwitchStatement
import org.codehaus.groovy.ast.stmt.TryCatchStatement
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KitchenSinkCoverageTest {

    // Use the test's classloader to ensure Groovy classes (like @CompileStatic) are available
    private val parser = GroovyParserFacade(GroovyParserFacade::class.java.classLoader)

    @Test
    fun `parse and visit kitchen sink file covers many node types`() {
        val content = loadKitchenSinkContent()
        val uri = URI.create("file:///kitchen-sink.groovy")

        val result = parser.parse(
            ParseRequest(
                uri = uri,
                content = content,
            ),
        )

        assertParseOk(result)
        val mainClass = assertAstModel(result)
        // assertSymbols(result, mainClass) - moved to semantics module
        assertHoverFormatting(mainClass)
    }

    private fun loadKitchenSinkContent(): String {
        // Use classpath resource for portability across Gradle and Bazel
        return this::class.java.getResource("/kitchen-sink.groovy")?.readText()
            ?: error("Kitchen sink file not found on classpath at /kitchen-sink.groovy")
    }

    private fun assertParseOk(result: ParseResult) {
        assertTrue(result.isSuccessful, "Parsing failed with: ${result.diagnostics}")
        assertNotNull(result.ast, "AST should not be null")
    }

    private fun assertAstModel(result: ParseResult): ClassNode {
        val astModel = result.astModel
        val allNodes = astModel.getAllNodes()
        val classNodes = astModel.getAllClassNodes()

        val mainClass =
            assertNotNull(classNodes.find { it.name == "com.example.KitchenSink" }, "Should find KitchenSink class")

        assertNotNull(
            classNodes.find { it.name == "com.example.KitchenSink\$Inner" },
            "Should find Inner class",
        )

        assertNotNull(
            allNodes.filterIsInstance<TryCatchStatement>().firstOrNull(),
            "Should find TryCatchStatement",
        )

        assertNotNull(
            allNodes.filterIsInstance<GStringExpression>().firstOrNull(),
            "Should find GStringExpression",
        )

        assertNotNull(
            allNodes.filterIsInstance<SwitchStatement>().firstOrNull(),
            "Should find SwitchStatement (Testing coverage/bug)",
        )

        assertNotNull(
            allNodes.filterIsInstance<CaseStatement>().firstOrNull(),
            "Should find CaseStatement",
        )

        return mainClass
    }

    private fun assertHoverFormatting(mainClass: ClassNode) {
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
