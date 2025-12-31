package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.Position
import com.github.albertocavalcante.groovyparser.Range
import com.github.albertocavalcante.groovyparser.ast.AnnotationExpr
import com.github.albertocavalcante.groovyparser.ast.BlockComment
import com.github.albertocavalcante.groovyparser.ast.LineComment
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.expr.EmptyExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.utils.NodeUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeClonerTest {

    @Test
    fun `clone CompilationUnit creates independent copy`() {
        val code = """
            package com.example
            
            import java.util.List
            
            class Foo {
                String name
                def greet() { println "Hello" }
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)
        assertTrue(result.isSuccessful)

        val original = result.result.get()
        val cloned = original.clone()

        // Should be different objects
        assertNotSame(original, cloned)

        // But have same structure
        assertEquals(
            original.packageDeclaration.orElse(null)?.name,
            cloned.packageDeclaration.orElse(null)?.name,
        )
        assertEquals(original.imports.size, cloned.imports.size)
        assertEquals(original.types.size, cloned.types.size)

        // Cloned types should be different objects
        assertNotSame(original.types[0], cloned.types[0])
        assertEquals(original.types[0].name, cloned.types[0].name)
    }

    @Test
    fun `clone ClassDeclaration preserves all properties`() {
        val code = """
            class Foo extends Bar implements Baz, Qux {
                static String name
                final int count = 0
                
                def greet(String message) {
                    println message
                }
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)
        assertTrue(result.isSuccessful)

        val original = result.result.get().types[0] as ClassDeclaration
        val cloned = original.clone()

        assertNotSame(original, cloned)
        assertEquals(original.name, cloned.name)
        assertEquals(original.superClass, cloned.superClass)
        assertEquals(original.implementedTypes.size, cloned.implementedTypes.size)
        assertEquals(original.fields.size, cloned.fields.size)
        assertEquals(original.methods.size, cloned.methods.size)

        // Fields should be cloned
        original.fields.zip(cloned.fields).forEach { (origField, clonedField) ->
            assertNotSame(origField, clonedField)
            assertEquals(origField.name, clonedField.name)
            assertEquals(origField.type, clonedField.type)
        }
    }

    @Test
    fun `clone MethodDeclaration preserves body and parameters`() {
        val code = """
            class Foo {
                int add(int a, int b) {
                    return a + b
                }
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)
        assertTrue(result.isSuccessful)

        val classDecl = result.result.get().types[0] as ClassDeclaration
        val original = classDecl.methods.find { it.name == "add" }!!
        val cloned = original.clone()

        assertNotSame(original, cloned)
        assertEquals(original.name, cloned.name)
        assertEquals(original.returnType, cloned.returnType)
        assertEquals(original.parameters.size, cloned.parameters.size)

        // Parameters should be cloned
        original.parameters.zip(cloned.parameters).forEach { (origParam, clonedParam) ->
            assertNotSame(origParam, clonedParam)
            assertEquals(origParam.name, clonedParam.name)
            assertEquals(origParam.type, clonedParam.type)
        }

        // Body should be cloned
        assertNotNull(cloned.body)
        assertNotSame(original.body, cloned.body)
    }

    @Test
    fun `clone preserves range information`() {
        val code = "class Foo {}"

        val parser = GroovyParser()
        val result = parser.parse(code)
        assertTrue(result.isSuccessful)

        val original = result.result.get()
        val cloned = original.clone()

        // Range should be copied
        original.types[0].range?.let { origRange ->
            cloned.types[0].range?.let { clonedRange ->
                assertEquals(origRange.begin.line, clonedRange.begin.line)
                assertEquals(origRange.begin.column, clonedRange.begin.column)
                assertEquals(origRange.end.line, clonedRange.end.line)
                assertEquals(origRange.end.column, clonedRange.end.column)
            }
        }
    }

    @Test
    fun `cloned node has no parent`() {
        val code = """
            class Foo {
                def method() {}
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)
        assertTrue(result.isSuccessful)

        val classDecl = result.result.get().types[0] as ClassDeclaration
        val originalMethod = classDecl.methods.find { it.name == "method" }!!

        // Original has parent
        assertNotNull(originalMethod.parentNode)

        // Clone has no parent
        val clonedMethod = originalMethod.clone()
        assertNull(clonedMethod.parentNode)
    }

    @Test
    fun `clone expressions in method body`() {
        val code = """
            class Foo {
                def test() {
                    def x = 1 + 2
                    def list = [1, 2, 3]
                    def map = [a: 1, b: 2]
                    println "Hello"
                }
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)
        assertTrue(result.isSuccessful)

        val original = result.result.get()
        val cloned = original.clone()

        // All method calls should be cloned
        val originalCalls = NodeUtils.findAll<MethodCallExpr>(original)
        val clonedCalls = NodeUtils.findAll<MethodCallExpr>(cloned)

        assertEquals(originalCalls.size, clonedCalls.size)

        // Each should be different objects
        originalCalls.zip(clonedCalls).forEach { (orig, clone) ->
            assertNotSame(orig, clone)
        }
    }

    @Test
    fun `clone handles control flow statements`() {
        val code = """
            class Foo {
                def test(x) {
                    if (x > 0) {
                        println "positive"
                    } else {
                        println "non-positive"
                    }
                    
                    for (i in 1..10) {
                        println i
                    }
                    
                    while (x > 0) {
                        x--
                    }
                    
                    try {
                        doSomething()
                    } catch (Exception e) {
                        handleError(e)
                    }
                }
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)
        assertTrue(result.isSuccessful)

        val original = result.result.get()
        val cloned = original.clone()

        // Should clone without errors
        assertNotSame(original, cloned)

        // Node count should match
        val originalCount = NodeUtils.countNodes(original)
        val clonedCount = NodeUtils.countNodes(cloned)
        assertEquals(originalCount, clonedCount)
    }

    @Test
    fun `clone preserves comments`() {
        val code = """
            /** Class comment */
            class Foo {
                /** Method comment */
                def bar() {}
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)
        assertTrue(result.isSuccessful)

        val original = result.result.get()
        val cloned = original.clone()

        val origClass = original.types[0]
        val clonedClass = cloned.types[0]

        // Comments should be cloned
        if (origClass.comment != null) {
            assertNotNull(clonedClass.comment)
            assertNotSame(origClass.comment, clonedClass.comment)
            assertEquals(origClass.comment?.content, clonedClass.comment?.content)
        }
    }

    @Test
    fun `clone preserves annotations`() {
        val code = """
            @Deprecated
            class Foo {
                @Override
                def toString() { "Foo" }
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)
        assertTrue(result.isSuccessful)

        val original = result.result.get()
        val cloned = original.clone()

        val origClass = original.types[0]
        val clonedClass = cloned.types[0]

        assertEquals(origClass.annotations.size, clonedClass.annotations.size)

        // Annotations should be different objects
        if (origClass.annotations.isNotEmpty()) {
            assertNotSame(origClass.annotations[0], clonedClass.annotations[0])
        }
    }

    @Test
    fun `extension function clone works`() {
        val code = "class Foo {}"

        val parser = GroovyParser()
        val result = parser.parse(code)
        assertTrue(result.isSuccessful)

        val original = result.result.get()
        val cloned = original.clone() // Using extension function

        assertNotSame(original, cloned)
        assertEquals(original.types.size, cloned.types.size)
    }

    @Test
    fun `cloning CatchClause directly preserves all properties including range`() {
        val code = """
            try {
                risky()
            } catch (Exception e) {
                handle(e)
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)
        assertTrue(result.isSuccessful)

        // Extract the TryCatchStatement from parsed code
        val cu = result.result.get()
        val classBody = cu.types[0].members
        // Code is at top level as script, find the try-catch

        // For testing, create a CatchClause manually with range
        val catchClause = com.github.albertocavalcante.groovyparser.ast.stmt.CatchClause(
            parameter = com.github.albertocavalcante.groovyparser.ast.body.Parameter(
                name = "e",
                type = "Exception",
            ),
            body = com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement(),
        )
        catchClause.range = Range(Position(3, 0), Position(5, 1))

        val cloned = catchClause.clone()

        assertNotSame(catchClause, cloned)
        assertNotSame(catchClause.parameter, cloned.parameter)
        assertNotSame(catchClause.body, cloned.body)
        assertEquals(catchClause.parameter.name, cloned.parameter.name)

        // Verify range is copied
        assertNotNull(cloned.range, "Cloned CatchClause should preserve range information")
        assertEquals(catchClause.range, cloned.range)
        assertNotSame(catchClause.range, cloned.range, "Range should be a deep copy")
    }

    @Test
    fun `cloning CaseStatement directly preserves all properties including range`() {
        // For testing, create a CaseStatement manually with range
        val caseStatement = com.github.albertocavalcante.groovyparser.ast.stmt.CaseStatement(
            expression = com.github.albertocavalcante.groovyparser.ast.expr.ConstantExpr(1),
            body = com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement(),
        )
        caseStatement.range = Range(Position(2, 4), Position(3, 12))

        val cloned = caseStatement.clone()

        assertNotSame(caseStatement, cloned)
        assertNotSame(caseStatement.expression, cloned.expression)
        assertNotSame(caseStatement.body, cloned.body)

        // Verify range is copied
        assertNotNull(cloned.range, "Cloned CaseStatement should preserve range information")
        assertEquals(caseStatement.range, cloned.range)
        assertNotSame(caseStatement.range, cloned.range, "Range should be a deep copy")
    }

    @Test
    fun `EmptyExpr instances are independent and not singleton`() {
        // Issue 4: EmptyExpr should be a class, not an object singleton
        // Multiple instances should be independent and not share mutable state
        val empty1 = EmptyExpr()
        val empty2 = EmptyExpr()

        // Should be different instances (not singleton)
        assertNotSame(empty1, empty2, "EmptyExpr instances should be independent, not singleton")

        // Test range independence
        empty1.range = Range(Position(1, 1), Position(1, 5))
        empty2.range = Range(Position(2, 1), Position(2, 10))
        assertNotNull(empty1.range)
        assertNotNull(empty2.range)
        assertTrue(empty1.range != empty2.range, "EmptyExpr instances should not share range state")

        // Test comment independence
        val comment1 = LineComment("Comment 1")
        val comment2 = LineComment("Comment 2")
        empty1.setComment(comment1)
        empty2.setComment(comment2)
        assertNotSame(empty1.comment, empty2.comment, "EmptyExpr instances should not share comment")
        assertEquals("Comment 1", empty1.comment?.content)
        assertEquals("Comment 2", empty2.comment?.content)

        // Test annotation independence
        val annotation1 = AnnotationExpr("Test1")
        val annotation2 = AnnotationExpr("Test2")
        empty1.addAnnotation(annotation1)
        empty2.addAnnotation(annotation2)
        assertEquals(1, empty1.annotations.size, "empty1 should have 1 annotation")
        assertEquals(1, empty2.annotations.size, "empty2 should have 1 annotation")
        assertEquals("Test1", empty1.annotations[0].name)
        assertEquals("Test2", empty2.annotations[0].name)

        // Test orphan comments independence
        val orphan1 = BlockComment("Orphan 1")
        val orphan2 = BlockComment("Orphan 2")
        empty1.addOrphanComment(orphan1)
        empty2.addOrphanComment(orphan2)
        assertEquals(1, empty1.orphanComments.size)
        assertEquals(1, empty2.orphanComments.size)
        assertNotSame(empty1.orphanComments[0], empty2.orphanComments[0])

        // Clone should create a new instance with ALL properties preserved
        val cloned = empty1.clone()
        assertNotSame(empty1, cloned, "Cloned EmptyExpr should be a different instance")

        // Verify range cloned
        assertNotNull(cloned.range, "Cloned EmptyExpr should preserve range")
        assertEquals(empty1.range, cloned.range, "Cloned range should match original")
        assertNotSame(empty1.range, cloned.range, "Cloned range should be a deep copy")

        // Verify comment cloned
        assertNotNull(cloned.comment, "Cloned EmptyExpr should preserve comment")
        assertEquals(empty1.comment?.content, cloned.comment?.content)
        assertNotSame(empty1.comment, cloned.comment, "Cloned comment should be a deep copy")

        // Verify annotations cloned
        assertEquals(empty1.annotations.size, cloned.annotations.size)
        assertNotSame(empty1.annotations[0], cloned.annotations[0], "Cloned annotation should be a deep copy")
        assertEquals(empty1.annotations[0].name, cloned.annotations[0].name)

        // Verify orphan comments cloned
        assertEquals(empty1.orphanComments.size, cloned.orphanComments.size)
        assertNotSame(empty1.orphanComments[0], cloned.orphanComments[0], "Cloned orphan comment should be a deep copy")

        // Mutating cloned range shouldn't affect original
        cloned.range = Range(Position(3, 1), Position(3, 20))
        assertTrue(empty1.range != cloned.range, "Mutating clone should not affect original")
    }
}
