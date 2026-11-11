package com.github.albertocavalcante.gvy.common.text

import org.junit.jupiter.api.Test
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

    @Test
    fun `blankCommentsAndStrings keeps the length and every newline`() {
        val code = "a = \'\'\'x\n}\n\'\'\' /* {\n */ b // }\nc"

        val blanked = GroovyCodeCleaner.blankCommentsAndStrings(code)

        assertEquals(code.length, blanked.length)
        assertEquals(code.indices.filter { code[it] == '\n' }, blanked.indices.filter { blanked[it] == '\n' })
        assertEquals("a =     \n \n        \n    b     \nc", blanked)
    }

    @Test
    fun `blankCommentsAndStrings blanks single and double quoted strings with escapes`() {
        assertEquals("echo      ; x", GroovyCodeCleaner.blankCommentsAndStrings("echo '}\\''; x"))
        assertEquals("echo        x", GroovyCodeCleaner.blankCommentsAndStrings("echo \"{\\\"}\" x"))
    }

    @Test
    fun `blankCommentsAndStrings blanks dollar-slashy and slashy strings but not division`() {
        assertEquals("j =        ;", GroovyCodeCleaner.blankCommentsAndStrings("j = ${'$'}/{ }/${'$'};"))
        assertEquals("r =     ;", GroovyCodeCleaner.blankCommentsAndStrings("r = /a{/;"))
        assertEquals("h = t / 2 / c", GroovyCodeCleaner.blankCommentsAndStrings("h = t / 2 / c"))
    }

    @Test
    fun `blankCommentsAndStrings ends an unterminated quoted string at the end of its line`() {
        assertEquals("a =       \nb { }", GroovyCodeCleaner.blankCommentsAndStrings("a = 'stray\nb { }"))
        assertEquals("a =       \nb { }", GroovyCodeCleaner.blankCommentsAndStrings("a = \"stray\nb { }"))
    }

    @Test
    fun `blankCommentsAndStrings treats return, case, in and assert as the start of a slashy string`() {
        assertEquals("case       : x", GroovyCodeCleaner.blankCommentsAndStrings("case /it's/: x"))
        assertEquals("return      ", GroovyCodeCleaner.blankCommentsAndStrings("return /a{}/"))
        assertEquals("x in      ", GroovyCodeCleaner.blankCommentsAndStrings("x in /b}c/"))
        assertEquals("assert      ", GroovyCodeCleaner.blankCommentsAndStrings("assert /d{e/"))
        assertEquals("total / count", GroovyCodeCleaner.blankCommentsAndStrings("total / count"))
    }

    @Test
    fun `blankCommentsAndStrings treats a closure arrow as the start of a slashy string`() {
        assertEquals("x.find { it ->       }", GroovyCodeCleaner.blankCommentsAndStrings("x.find { it -> /a{}/ }"))
        assertEquals("a > b / c", GroovyCodeCleaner.blankCommentsAndStrings("a > b / c"))
    }

    @Test
    fun `blankCommentsAndStrings keeps a GString open across a multi-line interpolation`() {
        val code = "s = \"${'$'}{ items.collect {\n it } }\" ; b {"

        val blanked = GroovyCodeCleaner.blankCommentsAndStrings(code)

        assertEquals("s =                    \n         ; b {", blanked)
        assertEquals("t =       \nb {", GroovyCodeCleaner.blankCommentsAndStrings("t = \"stray\nb {"))
    }

    @Test
    fun `blankCommentsAndStrings skips an escaped brace inside a GString interpolation`() {
        val literal = "\"${'$'}{ s.replace('\\{', '') }\""

        val blanked = GroovyCodeCleaner.blankCommentsAndStrings("$literal ; b {")

        assertEquals(" ".repeat(literal.length) + " ; b {", blanked)
    }
}
