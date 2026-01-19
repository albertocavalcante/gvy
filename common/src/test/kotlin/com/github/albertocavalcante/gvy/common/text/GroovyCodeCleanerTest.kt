package com.github.albertocavalcante.gvy.common.text

import kotlin.test.Test
import kotlin.test.assertEquals

class GroovyCodeCleanerTest {

    @Test
    fun `stripSingleLineComments removes single line comments`() {
        val input = "def foo = 'bar' // This is a comment"
        val expected = "def foo = 'bar' "
        assertEquals(expected, GroovyCodeCleaner.stripSingleLineComments(input))
    }

    @Test
    fun `stripSingleLineComments returns full line when no comment`() {
        val input = "def foo = 'bar'"
        assertEquals(input, GroovyCodeCleaner.stripSingleLineComments(input))
    }

    @Test
    fun `removeCommentsAndStrings removes single line comments`() {
        val input = "def foo // comment"
        val expected = "def foo "
        assertEquals(expected, GroovyCodeCleaner.removeCommentsAndStrings(input))
    }

    @Test
    fun `removeCommentsAndStrings removes multi-line comments`() {
        val input = "def foo /* comment */ bar"
        val expected = "def foo  bar"
        assertEquals(expected, GroovyCodeCleaner.removeCommentsAndStrings(input))
    }

    @Test
    fun `removeCommentsAndStrings removes single-quoted strings`() {
        val input = "def foo = 'bar'"
        val expected = "def foo = "
        assertEquals(expected, GroovyCodeCleaner.removeCommentsAndStrings(input))
    }

    @Test
    fun `removeCommentsAndStrings removes double-quoted strings`() {
        val input = "def foo = \"bar\""
        val expected = "def foo = "
        assertEquals(expected, GroovyCodeCleaner.removeCommentsAndStrings(input))
    }

    @Test
    fun `removeCommentsAndStrings removes triple-quoted strings`() {
        val input = "def foo = '''bar'''"
        val expected = "def foo = "
        assertEquals(expected, GroovyCodeCleaner.removeCommentsAndStrings(input))
    }

    @Test
    fun `removeCommentsAndStrings removes triple-double-quoted strings`() {
        val input = "def foo = \"\"\"bar\"\"\""
        val expected = "def foo = "
        assertEquals(expected, GroovyCodeCleaner.removeCommentsAndStrings(input))
    }

    @Test
    fun `removeCommentsAndStrings handles escaped quotes in strings`() {
        val input = "def foo = 'bar\\'baz'"
        val expected = "def foo = "
        assertEquals(expected, GroovyCodeCleaner.removeCommentsAndStrings(input))
    }

    @Test
    fun `removeCommentsAndStrings preserves code outside strings and comments`() {
        val input = "import spock.lang.Specification"
        assertEquals(input, GroovyCodeCleaner.removeCommentsAndStrings(input))
    }

    @Test
    fun `removeCommentsAndStrings handles spock block labels correctly`() {
        val input = """
            class MySpec extends Specification {
                def 'test'() {
                    given: 'some setup'
                    def x = 1

                    when: 'action'
                    x++

                    then: 'verify'
                    x == 2
                }
            }
        """.trimIndent()

        val result = GroovyCodeCleaner.removeCommentsAndStrings(input)

        // The string literals should be removed
        assert(!result.contains("'test'"))
        assert(!result.contains("'some setup'"))
        assert(!result.contains("'action'"))
        assert(!result.contains("'verify'"))

        // But the block labels (without strings) should remain
        assert(result.contains("given:"))
        assert(result.contains("when:"))
        assert(result.contains("then:"))
    }

    @Test
    fun `removeCommentsAndStrings avoids false positives from comments`() {
        val input = """
            // import spock.lang.Specification
            class MyClass {
            }
        """.trimIndent()

        val result = GroovyCodeCleaner.removeCommentsAndStrings(input)

        // The comment should be removed
        assert(!result.contains("import spock.lang.Specification"))
    }

    @Test
    fun `removeCommentsAndStrings avoids false positives from strings`() {
        val input = """
            def text = "import spock.lang.Specification"
            class MyClass {
            }
        """.trimIndent()

        val result = GroovyCodeCleaner.removeCommentsAndStrings(input)

        // The string content should be removed
        assert(!result.contains("import spock.lang.Specification"))
    }
}
