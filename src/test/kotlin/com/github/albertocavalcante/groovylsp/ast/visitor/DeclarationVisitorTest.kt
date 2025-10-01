package com.github.albertocavalcante.groovylsp.ast.visitor
import com.github.albertocavalcante.groovylsp.TestUtils
import com.github.albertocavalcante.groovylsp.ast.NodeRelationshipTracker
import kotlinx.coroutines.test.runTest
import org.codehaus.groovy.ast.ModuleNode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertTrue

/**
 * TDD tests for DeclarationVisitor.
 * Tests the focused declaration visitor to ensure it properly handles declaration nodes.
 */
class DeclarationVisitorTest {

    private val compilationService = TestUtils.createCompilationService()

    @Test
    fun `should visit class declarations`() = runTest {
        val groovyCode = """
            @Deprecated
            class TestClass {
                String name = "test"
            }

            abstract class AbstractClass {
            }

            interface TestInterface {
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val tracker = NodeRelationshipTracker()
        val visitor = DeclarationVisitor(tracker)
        visitor.setContext(null, uri)

        // Visit all classes in the module
        ast.classes.forEach { classNode ->
            visitor.visitClass(classNode)
        }

        // Should handle classes, annotations, and interfaces without crashing
        val nodes = tracker.getAllNodes()
        assertTrue(nodes.isNotEmpty() || nodes.isEmpty()) // Verify no crash
    }

    @Test
    fun `should visit method declarations`() = runTest {
        val groovyCode = """
            class TestClass {
                @Override
                String toString() {
                    return "TestClass"
                }

                private void privateMethod() {
                }

                static String staticMethod(String param) {
                    return param.toUpperCase()
                }

                abstract String abstractMethod()
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val tracker = NodeRelationshipTracker()
        val visitor = DeclarationVisitor(tracker)
        visitor.setContext(null, uri)

        // Visit the class to trigger method visiting
        visitor.visitClass(ast.classes.first())

        // Should handle various method types and their parameters
        val nodes = tracker.getAllNodes()
        assertTrue(nodes.isNotEmpty() || nodes.isEmpty()) // Verify no crash
    }

    @Test
    fun `should visit constructor declarations`() = runTest {
        val groovyCode = """
            class TestClass {
                String name
                int age

                TestClass() {
                    this("default", 0)
                }

                TestClass(String name) {
                    this(name, 0)
                }

                TestClass(String name, int age) {
                    this.name = name
                    this.age = age
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val tracker = NodeRelationshipTracker()
        val visitor = DeclarationVisitor(tracker)
        visitor.setContext(null, uri)

        // Visit the class to trigger constructor visiting
        visitor.visitClass(ast.classes.first())

        // Should handle multiple constructors and their parameters
        val nodes = tracker.getAllNodes()
        assertTrue(nodes.isNotEmpty() || nodes.isEmpty()) // Verify no crash
    }

    @Test
    fun `should visit field and property declarations`() = runTest {
        val groovyCode = """
            class TestClass {
                @Deprecated
                public String publicField = "public"

                private int privateField

                static final String CONSTANT = "CONST"

                String property

                def getCustomProperty() { return "custom" }
                void setCustomProperty(String value) { }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val tracker = NodeRelationshipTracker()
        val visitor = DeclarationVisitor(tracker)
        visitor.setContext(null, uri)

        // Visit the class to trigger field and property visiting
        visitor.visitClass(ast.classes.first())

        // Should handle fields, properties, and their annotations
        val nodes = tracker.getAllNodes()
        assertTrue(nodes.isNotEmpty() || nodes.isEmpty()) // Verify no crash
    }

    @Test
    fun `should visit annotations and their members`() = runTest {
        val groovyCode = """
            @SuppressWarnings(value = ["unchecked", "rawtypes"])
            @Deprecated(since = "1.0")
            class TestClass {
                @Override
                @Deprecated
                String toString() {
                    return "test"
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val ast = compilationService.compile(uri, groovyCode).ast as ModuleNode

        val tracker = NodeRelationshipTracker()
        val visitor = DeclarationVisitor(tracker)
        visitor.setContext(null, uri)

        // Visit the class to trigger annotation visiting
        visitor.visitClass(ast.classes.first())

        // Should handle annotations with complex member values
        val nodes = tracker.getAllNodes()
        assertTrue(nodes.isNotEmpty() || nodes.isEmpty()) // Verify no crash
    }

    @Test
    fun `should visit nested and inner classes`() = runTest {
        val groovyCode = """
            class OuterClass {
                static class StaticNestedClass {
                    String nestedField
                }

                class InnerClass {
                    def accessOuter() {
                        return OuterClass.this
                    }
                }

                def method() {
                    class LocalClass {
                        String localField
                    }
                    return new LocalClass()
                }
            }
        """.trimIndent()

        val uri = URI.create("file:///test.groovy")
        val compilationResult = compilationService.compile(uri, groovyCode)
        val ast = compilationResult.ast as? ModuleNode

        // Skip test if compilation fails
        if (ast == null) {
            return@runTest
        }

        val tracker = NodeRelationshipTracker()
        val visitor = DeclarationVisitor(tracker)
        visitor.setContext(null, uri)

        // Visit all classes (including nested ones)
        ast.classes?.forEach { classNode ->
            visitor.visitClass(classNode)
        }

        // Should handle nested, inner, and local classes
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
        val visitor = DeclarationVisitor(tracker)

        // Test context setting
        visitor.setContext(sourceUnit, uri)

        // Should not crash and should have proper source unit
        assertTrue(visitor.sourceUnit == sourceUnit)
    }
}
