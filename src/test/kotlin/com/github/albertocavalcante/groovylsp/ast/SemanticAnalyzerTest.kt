package com.github.albertocavalcante.groovylsp.ast

import kotlinx.coroutines.test.runTest
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.io.StringReaderSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the SemanticAnalyzer component.
 */
class SemanticAnalyzerTest {

    private val semanticAnalyzer = SemanticAnalyzer()

    @Test
    fun `should analyze simple class structure`() = runTest {
        val groovyCode = """
            class TestClass {
                String name
                int age

                def getName() {
                    return name
                }

                def setAge(int newAge) {
                    this.age = newAge
                }
            }
        """.trimIndent()

        val module = compileToModule(groovyCode)
        val result = semanticAnalyzer.analyzeModule(module)

        assertNotNull(result)
        assertNotNull(result.scopeTree)
        assertNotNull(result.semanticModel)

        // Check that symbols were extracted
        val symbols = result.semanticModel.symbols
        assertTrue(symbols.containsKey("TestClass"), "Should contain TestClass symbol")
        assertTrue(symbols.containsKey("name"), "Should contain name field symbol")
        assertTrue(symbols.containsKey("age"), "Should contain age field symbol")
        assertTrue(symbols.containsKey("getName"), "Should contain getName method symbol")
        assertTrue(symbols.containsKey("setAge"), "Should contain setAge method symbol")
    }

    @Test
    fun `should analyze scope hierarchy`() = runTest {
        val groovyCode = """
            class OuterClass {
                def outerMethod() {
                    def localVar = "test"

                    for (int i = 0; i < 10; i++) {
                        def loopVar = i * 2
                        println(loopVar)
                    }
                }
            }
        """.trimIndent()

        val module = compileToModule(groovyCode)
        val result = semanticAnalyzer.analyzeModule(module)

        val scopeTree = result.scopeTree
        assertNotNull(scopeTree.root)

        // Root should be module scope
        assertEquals(ScopeType.MODULE, scopeTree.root.type)

        // Should have class scope as child
        val classScopes = scopeTree.root.children.filter { it.type == ScopeType.CLASS }
        assertEquals(1, classScopes.size, "Should have one class scope")

        val classScope = classScopes.first()

        // Should have method scope as child of class
        val methodScopes = classScope.children.filter { it.type == ScopeType.METHOD }
        assertEquals(1, methodScopes.size, "Should have one method scope")
    }

    @Test
    fun `should find semantic info at position`() = runTest {
        val groovyCode = """
            class TestClass {
                String testField

                def testMethod() {
                    def localVar = "hello"
                    return testField + localVar
                }
            }
        """.trimIndent()

        val module = compileToModule(groovyCode)
        val result = semanticAnalyzer.analyzeModule(module)

        // Try to find semantic info at a position inside the method
        val semanticInfo = semanticAnalyzer.findSemanticInfoAtPosition(result, 4, 20) // Inside method body

        assertNotNull(semanticInfo, "Should find semantic info at position")
        assertNotNull(semanticInfo.scope, "Should have scope information")
        assertTrue(semanticInfo.availableCompletions.isNotEmpty(), "Should have completion suggestions")

        // Check that we have relevant completions
        val completionLabels = semanticInfo.availableCompletions.map { it.label }
        assertTrue(completionLabels.contains("testField"), "Should suggest testField")
        assertTrue(completionLabels.contains("testMethod"), "Should suggest testMethod")
    }

    @Test
    fun `should provide method completion suggestions`() = runTest {
        val groovyCode = """
            class TestClass {
                String getValue() { return "value" }
                void setValue(String value) { }
                int calculate(int a, int b) { return a + b }
            }
        """.trimIndent()

        val module = compileToModule(groovyCode)
        val result = semanticAnalyzer.analyzeModule(module)

        // Find semantic info inside class (should have method completions)
        val semanticInfo = semanticAnalyzer.findSemanticInfoAtPosition(result, 2, 10)

        assertNotNull(semanticInfo)
        val methodCompletions = semanticInfo.availableCompletions.filter { it.kind == CompletionKind.METHOD }

        assertTrue(methodCompletions.any { it.label == "getValue" }, "Should suggest getValue method")
        assertTrue(methodCompletions.any { it.label == "setValue" }, "Should suggest setValue method")
        assertTrue(methodCompletions.any { it.label == "calculate" }, "Should suggest calculate method")

        // Check that method completions have proper details
        val calculateCompletion = methodCompletions.find { it.label == "calculate" }
        assertNotNull(calculateCompletion)
        assertTrue(calculateCompletion.detail.contains("int"), "Should show return type")
    }

    /**
     * Helper method to compile Groovy code to a ModuleNode.
     */
    private fun compileToModule(groovyCode: String): ModuleNode {
        val config = CompilerConfiguration()
        val unit = CompilationUnit(config)

        val source = StringReaderSource(groovyCode, config)
        val sourceUnit = SourceUnit("TestFile.groovy", source, config, unit.classLoader, unit.errorCollector)

        unit.addSource(sourceUnit)
        unit.compile(Phases.SEMANTIC_ANALYSIS)

        return sourceUnit.ast
    }
}
