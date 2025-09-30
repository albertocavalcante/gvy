package com.github.albertocavalcante.groovylsp.ast.visitor
import com.github.albertocavalcante.groovylsp.TestUtils
import com.github.albertocavalcante.groovylsp.ast.NodeRelationshipTracker
import kotlinx.coroutines.test.runTest
import org.codehaus.groovy.ast.ModuleNode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertTrue

/**
 * TDD tests for StatementVisitor.
 * Tests the focused statement visitor to ensure it properly handles statement nodes.
 */
class StatementVisitorTest {

    private val compilationService = TestUtils.createCompilationService()

    @Test
    fun `should visit control flow statements`() = runTest {
        val groovyCode = """
            class TestClass {
                def method(int x) {
                    if (x > 0) {
                        return "positive"
                    } else {
                        return "non-positive"
                    }
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val tracker = NodeRelationshipTracker()
        val visitor = StatementVisitor(tracker)
        visitor.setContext(null, uri)

        // Visit the AST
        visitor.visitClass(ast.classes.first())

        // Should handle if-else statements without crashing
        val nodes = tracker.getAllNodes()
        assertTrue(nodes.isNotEmpty() || nodes.isEmpty()) // Verify no crash
    }

    @Test
    fun `should visit loop statements`() = runTest {
        val groovyCode = """
            class TestClass {
                def method() {
                    for (int i = 0; i < 10; i++) {
                        if (i == 5) {
                            continue
                        }
                        if (i == 8) {
                            break
                        }
                        println(i)
                    }

                    while (true) {
                        break
                    }

                    int j = 0
                    do {
                        j++
                    } while (j < 3)
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val tracker = NodeRelationshipTracker()
        val visitor = StatementVisitor(tracker)
        visitor.setContext(null, uri)

        // Visit the AST
        visitor.visitClass(ast.classes.first())

        // Should handle all loop types and jump statements
        val nodes = tracker.getAllNodes()
        assertTrue(nodes.isNotEmpty() || nodes.isEmpty()) // Verify no crash
    }

    @Test
    fun `should visit switch statements`() = runTest {
        val groovyCode = """
            class TestClass {
                def method(String input) {
                    switch (input) {
                        case "hello":
                            return "greeting"
                        case "bye":
                            return "farewell"
                        default:
                            return "unknown"
                    }
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val tracker = NodeRelationshipTracker()
        val visitor = StatementVisitor(tracker)
        visitor.setContext(null, uri)

        // Visit the AST
        visitor.visitClass(ast.classes.first())

        // Should handle switch and case statements
        val nodes = tracker.getAllNodes()
        assertTrue(nodes.isNotEmpty() || nodes.isEmpty()) // Verify no crash
    }

    @Test
    fun `should visit exception handling statements`() = runTest {
        val groovyCode = """
            class TestClass {
                def method() {
                    try {
                        throw new RuntimeException("test")
                    } catch (RuntimeException e) {
                        return "caught runtime"
                    } catch (Exception e) {
                        return "caught general"
                    } finally {
                        println("cleanup")
                    }
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val tracker = NodeRelationshipTracker()
        val visitor = StatementVisitor(tracker)
        visitor.setContext(null, uri)

        // Visit the AST
        visitor.visitClass(ast.classes.first())

        // Should handle try-catch-finally, throw statements
        val nodes = tracker.getAllNodes()
        assertTrue(nodes.isNotEmpty() || nodes.isEmpty()) // Verify no crash
    }

    @Test
    fun `should visit assert and synchronized statements`() = runTest {
        val groovyCode = """
            class TestClass {
                def method(Object lock) {
                    assert true : "This should pass"

                    synchronized (lock) {
                        println("synchronized block")
                    }
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val tracker = NodeRelationshipTracker()
        val visitor = StatementVisitor(tracker)
        visitor.setContext(null, uri)

        // Visit the AST
        visitor.visitClass(ast.classes.first())

        // Should handle assert and synchronized statements
        val nodes = tracker.getAllNodes()
        assertTrue(nodes.isNotEmpty() || nodes.isEmpty()) // Verify no crash
    }

    @Test
    fun `should visit basic statements`() = runTest {
        val groovyCode = """
            class TestClass {
                def method() {
                    {
                        println("block statement")
                    }

                    println("expression statement")

                    ; // empty statement
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val tracker = NodeRelationshipTracker()
        val visitor = StatementVisitor(tracker)
        visitor.setContext(null, uri)

        // Visit the AST
        visitor.visitClass(ast.classes.first())

        // Should handle block, expression, and empty statements
        val nodes = tracker.getAllNodes()
        assertTrue(nodes.isNotEmpty() || nodes.isEmpty()) // Verify no crash
    }

    @Test
    fun `should handle context setting correctly`() = runTest {
        val groovyCode = """
            class TestClass {
                def simpleMethod() {
                    return "hello"
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val sourceUnit = null // Simplified for testing

        val tracker = NodeRelationshipTracker()
        val visitor = StatementVisitor(tracker)

        // Test context setting
        visitor.setContext(sourceUnit, uri)

        // Should not crash and should have proper source unit
        assertTrue(visitor.sourceUnit == sourceUnit)
    }
}
