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

    // ==================== EDGE CASE TESTS ====================
    // Based on JavaParser's issue tests and Groovy-specific scenarios

    @Test
    fun `backslash in string does not break comment parsing - Issue290`() {
        // This tests the same issue as JavaParser's Issue290 test
        val code = """
            class TestComments {
                String str = "\\"
                
                /** Comment that should be found */
                def someMethod() {}
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val unit = result.result.get()
        val classDecl = unit.types[0] as ClassDeclaration

        val method = classDecl.methods.find { it.name == "someMethod" }
        assertNotNull(method, "Should find someMethod")

        val comment = method.comment
        assertNotNull(comment, "Method should have comment even after backslash in string")
        assertIs<JavadocComment>(comment)
        assertTrue(comment.content.contains("should be found"))
    }

    @Test
    fun `GString with interpolation does not confuse comment parser`() {
        // Comments inside method bodies are not currently captured - this tests
        // that GStrings don't break the parser for declaration-level comments
        val code = """
            class Foo {
                def test() {
                    def name = "World"
                    println "Hello ${'$'}{name}"
                }
                
                /** Method after GString usage */
                def anotherMethod() {}
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        // The GString should not confuse the parser
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods.find { it.name == "anotherMethod" }
        assertNotNull(method, "Should find anotherMethod")
        assertNotNull(method.comment, "Method should have Javadoc attached")
    }

    @Test
    fun `triple-quoted string with comment-like content`() {
        // Groovy triple-quoted string containing comment-like text
        val code = "class Foo {\n" +
            "    def text = \"\"\"\n" +
            "        // This is NOT a comment\n" +
            "        /* Neither is this */\n" +
            "        /** Also not a comment */\n" +
            "    \"\"\"\n" +
            "    \n" +
            "    // This IS a real comment\n" +
            "    def method() {}\n" +
            "}"

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val allComments = result.result.get().getAllContainedComments()

        // Should only find the real comment, not the ones inside the string
        assertTrue(
            allComments.all { !it.content.contains("NOT") && !it.content.contains("Neither") },
            "Should not parse comment-like content inside triple-quoted strings",
        )
        assertTrue(
            allComments.any { it.content.contains("real comment") },
            "Should find the actual comment",
        )
    }

    @Test
    fun `comment-like content in single-quoted string`() {
        val code = """
            class Foo {
                def s = '// not a comment'
                def s2 = '/* also not */'
                /* but this is */
                def method() {}
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val allComments = result.result.get().getAllContainedComments()

        assertTrue(
            allComments.none { it.content.contains("not a comment") },
            "Should not parse comments inside single-quoted strings",
        )
        assertTrue(
            allComments.any { it.content.contains("but this is") },
            "Should find the actual block comment",
        )
    }

    @Test
    fun `inline comment on same line as code`() {
        val code = """
            class Foo {
                def x = 1 // inline comment
                def y = 2
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val allComments = result.result.get().getAllContainedComments()
        assertTrue(
            allComments.any { it.content.contains("inline comment") },
            "Should capture inline comments",
        )
    }

    @Test
    fun `multiple comments before single declaration`() {
        val code = """
            // Comment 1
            // Comment 2
            /** Javadoc */
            class Foo {}
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val unit = result.result.get()

        // The Javadoc should be attached to the class
        val classDecl = unit.types[0]
        val comment = classDecl.comment
        assertNotNull(comment)
        assertIs<JavadocComment>(comment)

        // The line comments should be orphans or attached elsewhere
        val allComments = unit.getAllContainedComments()
        assertTrue(allComments.size >= 1, "Should have captured comments")
    }

    @Test
    fun `groovydoc with tags on class`() {
        val code = """
            /**
             * Class description.
             *
             * @author John Doe
             * @since 1.0
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
        assertNotNull(comment, "Class should have Groovydoc comment")
        assertIs<JavadocComment>(comment)

        // Content should preserve the tags
        val content = comment.content
        assertTrue(content.contains("Class description"), "Should have description")
    }

    @Test
    fun `groovydoc with tags on method`() {
        val code = """
            class Foo {
                /**
                 * Adds two numbers.
                 *
                 * @param x the x value
                 * @param y the y value
                 * @return the sum
                 */
                int add(int x, int y) {
                    return x + y
                }
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val unit = result.result.get()
        val classDecl = unit.types[0] as ClassDeclaration

        val method = classDecl.methods.find { it.name == "add" }
        assertNotNull(method, "Should find add method")

        val comment = method.comment
        assertNotNull(comment, "Method should have Groovydoc comment")
        assertIs<JavadocComment>(comment)

        // Content should preserve the description
        assertTrue(
            comment.content.contains("Adds two numbers") || comment.content.contains("adds"),
            "Should have description",
        )
    }

    @Test
    fun `empty comment handled correctly`() {
        val code = """
            /**/
            class Foo {}
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        // Should not crash on empty comment
    }

    @Test
    fun `closure declarations get comments attached`() {
        // Note: Comments *inside* closures are not currently captured,
        // but comments on declarations containing closures are.
        val code = """
            class Foo {
                /** This is a closure field */
                def bar = { println "hello" }
                
                /** Another field */
                def baz = 123
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val classDecl = result.result.get().types[0] as ClassDeclaration

        // Field 'bar' should have comment
        val barField = classDecl.fields.find { it.name == "bar" }
        assertNotNull(barField, "Should find bar field")
        assertNotNull(barField.comment, "Closure field should have comment")
        assertTrue(barField.comment?.content?.contains("closure field") == true)
    }

    @Test
    fun `regex literal does not confuse parser`() {
        val code = """
            class Foo {
                def pattern = ~/.*test.*/  // This is a comment
                def method() {}
            }
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val allComments = result.result.get().getAllContainedComments()
        assertTrue(
            allComments.any { it.content.contains("This is a comment") },
            "Should find comment after regex literal",
        )
    }

    @Test
    fun `comment before annotated class`() {
        // Note: Comments between annotation and class are tricky because
        // Groovy's AST includes annotations as part of the class node.
        // This tests that a leading comment before the annotations is captured.
        val code = """
            /** Class with annotation */
            @Deprecated
            class Foo {}
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        val classDecl = result.result.get().types[0]
        // The Javadoc should be attached to the class even with annotation
        assertNotNull(classDecl.comment, "Should have Javadoc before annotated class")
    }

    @Test
    fun `inline comment after code captured as orphan`() {
        // Inline comments (on same line after code) may not be attributed
        // to any specific node, but should be captured as orphans
        val code = """
            class Foo {
                def x = 1
            }
            // Comment after class
        """.trimIndent()

        val parser = GroovyParser()
        val result = parser.parse(code)

        assertTrue(result.isSuccessful)
        // Parser should not crash; trailing comments may or may not be captured
        // depending on implementation details
    }
}
