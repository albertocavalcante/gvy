package com.github.albertocavalcante.groovyparser.ast.visitor

import com.github.albertocavalcante.groovyparser.test.ParserTestFixture
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Comprehensive tests for visitor behavior - tracks parent-child relationships,
 * node collection, and proper AST traversal.
 */
class VisitorBehaviorTest {

    private val fixture = ParserTestFixture()

    @Test
    fun `tracks parent-child relationships correctly`() {
        val code = """
            class Foo {
                def bar() {
                    println "test"
                }
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful, "Parse should succeed")

        val visitor = result.astModel
        val classNode = result.ast!!.classes.find { it.name.contains("Foo") }
        assertNotNull(classNode, "Should find Foo class")

        val methodNode = classNode!!.methods.find { it.name == "bar" }
        assertNotNull(methodNode, "Should find bar method")

        // Verify parent relationship: method's parent should be class
        val methodParent = visitor.getParent(methodNode!!)
        assertEquals(classNode, methodParent, "Method's parent should be the class")

        // Verify we can traverse to method body
        val methodBody = methodNode.code
        assertNotNull(methodBody, "Method should have a body")

        val bodyParent = visitor.getParent(methodBody!!)
        assertEquals(methodNode, bodyParent, "Method body's parent should be the method")
    }

    @Test
    fun `visits all nodes in nested structures`() {
        val code = """
            class Outer {
                class Inner {
                    def innerMethod() {
                        def x = { println "nested closure" }
                    }
                }
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)

        val visitor = result.astModel
        val allNodes = visitor.getAllNodes()

        // Should have visited nested classes
        val classNodes = allNodes.filterIsInstance<ClassNode>()
        assertTrue(
            classNodes.size >= 2,
            "Should have at least Outer and Inner classes, found ${classNodes.size}",
        )

        // Should have visited methods
        val methodNodes = allNodes.filterIsInstance<MethodNode>()
        assertTrue(methodNodes.any { it.name == "innerMethod" }, "Should have visited innerMethod")

        // Should have visited closures
        val closureNodes = allNodes.filterIsInstance<ClosureExpression>()
        assertTrue(closureNodes.size >= 1, "Should have visited at least one closure")
    }

    @Test
    fun `filters synthetic nodes properly`() {
        val code = """
            def x = 1
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)

        val visitor = result.astModel
        val allNodes = visitor.getAllNodes()

        // All tracked nodes should have valid positions
        val nodesWithInvalidPositions = allNodes.filter { node ->
            node.lineNumber <= 0 || node.columnNumber <= 0
        }

        assertEquals(
            0,
            nodesWithInvalidPositions.size,
            "Should not track synthetic nodes with invalid positions: $nodesWithInvalidPositions",
        )
    }

    @Test
    fun `handles multiple files separately`() {
        val code1 = """
            class FileOne {
                def method1() {}
            }
        """.trimIndent()

        val code2 = """
            class FileTwo {
                def method2() {}
            }
        """.trimIndent()

        val uri1 = java.net.URI.create("file:///FileOne.groovy")
        val uri2 = java.net.URI.create("file:///FileTwo.groovy")

        val result1 = fixture.parse(code1, uri1.toString())
        val result2 = fixture.parse(code2, uri2.toString())

        assertTrue(result1.isSuccessful)
        assertTrue(result2.isSuccessful)

        // Both files should be tracked by the same visitor if we're reusing it
        // For now, each parse creates its own visitor, so test isolation
        val visitor1 = result1.astModel
        val visitor2 = result2.astModel

        val nodes1 = visitor1.getNodes(uri1)
        val nodes2 = visitor2.getNodes(uri2)

        assertTrue(nodes1.isNotEmpty(), "FileOne should have nodes")
        assertTrue(nodes2.isNotEmpty(), "FileTwo should have nodes")

        // FileOne's visitor should only know about FileOne
        val classes1 = visitor1.getAllClassNodes()
        assertTrue(classes1.any { it.name.contains("FileOne") })
    }

    @Test
    fun `tracks closure parameters`() {
        val code = """
            def closure = { param1, param2 ->
                println param1
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)

        val visitor = result.astModel
        val allNodes = visitor.getAllNodes()

        // Find the closure
        val closures = allNodes.filterIsInstance<ClosureExpression>()
        assertEquals(1, closures.size, "Should have exactly one closure")

        val closure = closures[0]
        assertNotNull(closure.parameters, "Closure should have parameters")
        assertTrue(
            closure.parameters.size >= 2,
            "Closure should have at least 2 parameters, found ${closure.parameters.size}",
        )

        // Parameters should be tracked as nodes
        val parameterNodes = allNodes.filterIsInstance<Parameter>()
        assertTrue(
            parameterNodes.size >= 2,
            "Should have tracked closure parameters, found ${parameterNodes.size}",
        )

        // Each closure parameter should have the closure as parent
        parameterNodes.forEach { param ->
            val parent = visitor.getParent(param)
            assertEquals(closure, parent, "Closure parameter ${param.name} should have closure as parent")
        }
    }

    @Test
    fun `tracks method parameters`() {
        val code = """
            class Foo {
                def method(String arg1, int arg2) {
                    println arg1
                }
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)

        val visitor = result.astModel
        val allNodes = visitor.getAllNodes()

        // Find method parameters
        val parameters = allNodes.filterIsInstance<Parameter>()
        assertTrue(
            parameters.size >= 2,
            "Should have tracked at least 2 method parameters, found ${parameters.size}",
        )

        // Verify parent relationship for parameters
        val method = allNodes.filterIsInstance<MethodNode>().find { it.name == "method" }
        assertNotNull(method, "Should find 'method'")

        parameters.forEach { param ->
            val parent = visitor.getParent(param)
            // Parent should be the method (or its parameter list conceptually)
            assertNotNull(parent, "Parameter ${param.name} should have a parent")
        }
    }

    @Test
    fun `tracks field initializer expressions`() {
        val code = """
            class Foo {
                def field1 = 42
                String field2 = "hello"
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)

        val visitor = result.astModel
        val allNodes = visitor.getAllNodes()

        val fields = allNodes.filterIsInstance<FieldNode>()
        assertTrue(fields.size >= 2, "Should have at least 2 fields, found ${fields.size}")

        fields.forEach { field ->
            val init = field.initialExpression
            assertNotNull(init, "Field ${field.name} should have an initializer")
            assertTrue(allNodes.contains(init), "Initializer for field ${field.name} should be tracked")
            val parent = visitor.getParent(init!!)
            assertEquals(field, parent, "Initializer for field ${field.name} should have the field as parent")
        }
    }

    @Test
    fun `tracks annotations and their members`() {
        val code = """
            @Deprecated(since = "1.2")
            class Foo {
                @SuppressWarnings(["unchecked"])
                def bar() {}
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)

        val visitor = result.astModel
        val allNodes = visitor.getAllNodes()

        val classNode = allNodes.filterIsInstance<ClassNode>().find { it.name.contains("Foo") }
        assertNotNull(classNode, "Should have tracked class Foo")

        // Annotation nodes should be tracked and attached to their owners
        val annotations = allNodes.filterIsInstance<org.codehaus.groovy.ast.AnnotationNode>()
        assertTrue(annotations.size >= 2, "Should have tracked class and method annotations")

        annotations.forEach { annotation ->
            val parent = visitor.getParent(annotation)
            assertNotNull(parent, "Annotation ${annotation.classNode?.name} should have a parent")
        }

        // Annotation members (e.g., since = "1.2") should be visited as expressions
        val memberLiteral = allNodes.filterIsInstance<ConstantExpression>()
            .find { it.value == "1.2" }
        assertNotNull(memberLiteral, "Should have tracked annotation member value \"1.2\"")

        val deprecatedAnnotation = annotations.find { annotation ->
            annotation.classNode?.name == "java.lang.Deprecated"
        }
        assertNotNull(deprecatedAnnotation, "Deprecated annotation should be tracked")
        assertEquals(
            deprecatedAnnotation,
            visitor.getParent(memberLiteral!!),
            "Annotation member should have annotation as parent",
        )
    }

    @Test
    fun `tracks field declarations`() {
        val code = """
            class Foo {
                def field1 = 42
                String field2 = "hello"
            }
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)

        val visitor = result.astModel
        val allNodes = visitor.getAllNodes()

        // Find fields - these should be tracked
        val fields = allNodes.filterIsInstance<FieldNode>()
        assertTrue(fields.size >= 2, "Should have at least 2 fields, found ${fields.size}")

        // Verify fields have initializers (even if not fully visited yet)
        val fieldsWithInit = fields.filter { it.initialExpression != null }
        assertTrue(
            fieldsWithInit.size >= 2,
            "Should have at least 2 fields with initializers, found ${fieldsWithInit.size}",
        )

        fieldsWithInit.forEach { field ->
            assertTrue(
                allNodes.contains(field.initialExpression),
                "Initializer for field ${field.name} should be tracked",
            )
        }
    }

    @Test
    fun `tracks import nodes`() {
        val code = """
            import java.util.List
            import static java.lang.Math.PI
            import java.util.concurrent.*

            class Foo {}
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)

        val visitor = result.astModel
        val allNodes = visitor.getAllNodes()

        // Find import nodes
        val imports = allNodes.filterIsInstance<org.codehaus.groovy.ast.ImportNode>()
        assertTrue(
            imports.size >= 3,
            "Should have tracked at least 3 import statements, found ${imports.size}",
        )

        // Verify import details (className might be null for some import types)
        assertTrue(
            imports.any { it.className?.contains("List") == true },
            "Should have List import",
        )
        assertTrue(
            imports.any { it.className?.contains("Math") == true },
            "Should have static Math import",
        )
    }

    @Test
    fun `tracks import and class header type references`() {
        val code = """
            import java.util.List
            import java.util.ArrayList

            class Foo extends ArrayList implements List {}
            def list = new ArrayList()
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)

        val visitor = result.astModel
        val allNodes = visitor.getAllNodes()
        val classNodes = allNodes.filterIsInstance<ClassNode>()

        assertTrue(classNodes.any { it.name == "java.util.List" }, "Should track List type reference")
        assertTrue(classNodes.any { it.name == "java.util.ArrayList" }, "Should track ArrayList type reference")
        assertTrue(
            visitor.getAllClassNodes().any { it.name.endsWith("Foo") },
            "Should track Foo class definition",
        )
    }

    @Test
    fun `tracks declaration expressions and their components`() {
        val code = """
            def x = 42
            def y = x + 1
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)

        val visitor = result.astModel
        val allNodes = visitor.getAllNodes()

        // Find declarations
        val declarations = allNodes.filterIsInstance<DeclarationExpression>()
        assertTrue(declarations.size >= 2, "Should have at least 2 declarations")

        // Each declaration should have left and right expressions tracked
        declarations.forEach { decl ->
            assertTrue(
                allNodes.contains(decl.leftExpression),
                "Declaration's left expression should be tracked",
            )
            assertTrue(
                allNodes.contains(decl.rightExpression),
                "Declaration's right expression should be tracked",
            )
        }
    }

    @Test
    fun `correctly handles script class synthetic nodes`() {
        val code = """
            println "Hello"
            def x = 1
        """.trimIndent()

        val result = fixture.parse(code)
        assertTrue(result.isSuccessful)

        val visitor = result.astModel

        // The script generates a synthetic script class
        val scriptClass = result.ast!!.scriptClassDummy
        assertNotNull(scriptClass, "Should have script class")

        // Script class might not be tracked (synthetic), but its run() method content should be
        val allNodes = visitor.getAllNodes()
        val expressionStatements = allNodes.filterIsInstance<ExpressionStatement>()
        assertTrue(
            expressionStatements.size >= 2,
            "Should track statements from script even if script class is synthetic",
        )
    }
}
