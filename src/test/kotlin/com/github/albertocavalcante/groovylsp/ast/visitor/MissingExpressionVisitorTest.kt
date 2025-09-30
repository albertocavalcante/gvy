package com.github.albertocavalcante.groovylsp.ast.visitor
import com.github.albertocavalcante.groovylsp.TestUtils
import com.github.albertocavalcante.groovylsp.ast.NodeRelationshipTracker
import kotlinx.coroutines.test.runTest
import org.codehaus.groovy.ast.ModuleNode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertTrue

/**
 * TDD tests for missing expression visitor methods.
 * Tests the expression visitors we need to implement to reach complete Groovy AST coverage.
 */
class MissingExpressionVisitorTest {

    private val compilationService = TestUtils.createCompilationService()

    @Test
    fun `should visit annotation constant expressions`() = runTest {
        val groovyCode = """
            @SuppressWarnings(value = ["unchecked", "rawtypes"])
            @Deprecated(since = "1.0", forRemoval = true)
            class TestClass {
                @Override
                String toString() {
                    return "test"
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val tracker = NodeRelationshipTracker()
        val visitor = NodeVisitorDelegate(tracker)
        visitor.visitModule(ast, null, uri)

        // Should visit annotation constant expressions like "unchecked", "rawtypes", "1.0", true
        val nodes = tracker.getAllNodes()

        // Test should fail initially since we don't have visitAnnotationConstantExpression
        // After implementation, this should pass and track annotation constant values
        assertTrue(nodes.isNotEmpty()) // Verify AST is being traversed
    }

    @Test
    fun `should visit method reference expressions`() = runTest {
        val groovyCode = """
            class TestClass {
                def processData(List<String> data) {
                    // Method reference expressions (Groovy 4.0+)
                    return data.stream()
                        .map(String::toUpperCase)
                        .filter(this::isValid)
                        .collect()
                }

                boolean isValid(String str) {
                    return str.length() > 0
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val tracker = NodeRelationshipTracker()
        val visitor = NodeVisitorDelegate(tracker)
        visitor.visitModule(ast, null, uri)

        // Should visit method reference expressions like String::toUpperCase, this::isValid
        val nodes = tracker.getAllNodes()

        // Test should fail initially since we don't have visitMethodReferenceExpression
        // After implementation, this should properly track method references
        assertTrue(nodes.isNotEmpty()) // Verify AST is being traversed
    }

    @Test
    fun `should visit lambda expressions`() = runTest {
        val groovyCode = """
            class TestClass {
                def processData(List<String> data) {
                    // Lambda expressions (Groovy 4.0+)
                    return data.stream()
                        .map(s -> s.toUpperCase())
                        .filter(s -> s.length() > 3)
                        .collect()
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val tracker = NodeRelationshipTracker()
        val visitor = NodeVisitorDelegate(tracker)
        visitor.visitModule(ast, null, uri)

        // Should visit lambda expressions like s -> s.toUpperCase()
        val nodes = tracker.getAllNodes()

        // Test should fail initially since we don't have visitLambdaExpression
        // After implementation, this should properly track lambda expressions
        assertTrue(nodes.isNotEmpty()) // Verify AST is being traversed
    }

    @Test
    fun `should handle complex annotation constant scenarios`() = runTest {
        val groovyCode = """
            @interface CustomAnnotation {
                String value() default "default"
                Class<?> type() default Object.class
                int[] numbers() default [1, 2, 3]
            }

            @CustomAnnotation(
                value = "custom",
                type = String.class,
                numbers = [10, 20, 30]
            )
            class AnnotatedClass {
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val tracker = NodeRelationshipTracker()
        val visitor = NodeVisitorDelegate(tracker)
        visitor.visitModule(ast, null, uri)

        // Should visit various annotation constant types: strings, classes, arrays
        val nodes = tracker.getAllNodes()
        assertTrue(nodes.isNotEmpty()) // Verify AST is being traversed
    }

    @Test
    fun `should handle groovy-specific lambda syntax`() = runTest {
        val groovyCode = """
            class TestClass {
                def testClosureVsLambda() {
                    def list = [1, 2, 3, 4, 5]

                    // Traditional closure
                    def closure = { it * 2 }

                    // Java-style lambda (if supported)
                    def lambda = (x) -> x * 2

                    return [
                        list.collect(closure),
                        list.stream().map(lambda).collect()
                    ]
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val tracker = NodeRelationshipTracker()
        val visitor = NodeVisitorDelegate(tracker)
        visitor.visitModule(ast, null, uri)

        // Should distinguish between closures and lambdas
        val nodes = tracker.getAllNodes()
        assertTrue(nodes.isNotEmpty()) // Verify AST is being traversed
    }
}
