package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.ast.BlockComment
import com.github.albertocavalcante.groovyparser.ast.JavadocComment
import com.github.albertocavalcante.groovyparser.ast.LineComment
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for comment preservation during parsing.
 */
class CommentPreservationTest {

    @Test
    fun `parse class with javadoc comment`() {
        val code = """
            /** This is a Javadoc comment for Foo. */
            class Foo {
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val unit = result.result.get()
        assertEquals(1, unit.types.size)

        val classDecl = unit.types[0]
        assertEquals("Foo", classDecl.name)

        // The class should have the Javadoc comment attached
        val comment = classDecl.comment
        assertNotNull(comment, "Class should have a comment attached")
        assertIs<JavadocComment>(comment)
        assertTrue(comment.content.contains("Javadoc comment"))
    }

    @Test
    fun `parse class with line comment`() {
        val code = """
            // This is a line comment for Bar
            class Bar {
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val unit = result.result.get()

        val classDecl = unit.types[0]
        val comment = classDecl.comment
        assertNotNull(comment, "Class should have a comment attached")
        assertIs<LineComment>(comment)
        assertTrue(comment.content.contains("line comment"))
    }

    @Test
    fun `parse class with block comment`() {
        val code = """
            /* This is a block comment */
            class Baz {
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val unit = result.result.get()

        val classDecl = unit.types[0]
        val comment = classDecl.comment
        assertNotNull(comment, "Class should have a comment attached")
        assertIs<BlockComment>(comment)
        assertTrue(comment.content.contains("block comment"))
    }

    @Test
    fun `parse method with javadoc comment`() {
        val code = """
            class Foo {
                /** Method documentation. */
                void bar() {
                }
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val unit = result.result.get()
        val classDecl = unit.types[0] as ClassDeclaration

        // Find the bar method (skip synthetic methods like run())
        val method = classDecl.methods.find { it.name == "bar" }
        assertNotNull(method, "Should have bar method")

        val comment = method.comment
        assertNotNull(comment, "Method should have a comment attached")
        assertIs<JavadocComment>(comment)
        assertTrue(comment.content.contains("Method documentation"))
    }

    @Test
    fun `parse field with comment`() {
        val code = """
            class Foo {
                /** Field documentation. */
                String name
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val unit = result.result.get()
        val classDecl = unit.types[0] as ClassDeclaration

        val field = classDecl.fields.find { it.name == "name" }
        assertNotNull(field, "Should have name field")

        val comment = field.comment
        assertNotNull(comment, "Field should have a comment attached")
        assertTrue(comment.content.contains("Field documentation"))
    }

    @Test
    fun `parse file with leading comment`() {
        val code = """
            // File header comment
            // Another line
            
            class Foo {
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val unit = result.result.get()

        // The compilation unit should have orphan comments for leading comments
        assertTrue(unit.orphanComments.isNotEmpty() || unit.types[0].comment != null)
    }

    @Test
    fun `comment attribution disabled returns no comments`() {
        val code = """
            /** This is a Javadoc comment for Foo. */
            class Foo {
            }
        """.trimIndent()

        val config = ParserConfiguration().setAttributeComments(false)
        val parser = GroovyParser(config)
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val unit = result.result.get()
        val classDecl = unit.types[0]

        // When comment attribution is disabled, no comments should be attached
        val comment = classDecl.comment
        assertEquals(null, comment, "Comment should not be attached when attribution is disabled")
    }

    @Test
    fun `getAllContainedComments returns all comments in AST`() {
        val code = """
            /** Class doc */
            class Foo {
                /** Method doc */
                void bar() {}
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val unit = result.result.get()

        val allComments = unit.getAllContainedComments()
        assertTrue(allComments.isNotEmpty(), "Should have found comments in AST")
    }

    @Test
    fun `comments have correct line positions`() {
        val code = """
            /** Doc on line 1 */
            class Foo {
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val unit = result.result.get()
        val classDecl = unit.types[0]

        val comment = classDecl.comment
        assertNotNull(comment)
        assertNotNull(comment.range)
        assertEquals(1, comment.range?.begin?.line)
    }

    @Test
    fun `multiline block comment preserved correctly`() {
        val code = """
            /*
             * This is a
             * multiline block
             * comment.
             */
            class Foo {
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val unit = result.result.get()
        val classDecl = unit.types[0]

        val comment = classDecl.comment
        assertNotNull(comment)
        assertIs<BlockComment>(comment)
        assertTrue(comment.content.contains("multiline"))
    }
}
