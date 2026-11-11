package com.github.albertocavalcante.gvy.fmt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class TokenStreamGuardTest {

    private fun assertSafe(original: String, edited: String) {
        val verdict = TokenStreamGuard.check(original, edited)
        assertThat(verdict.isSafe)
            .withFailMessage { "Expected a safe edit but got: ${verdict.describe()}\n--- edited:\n$edited" }
            .isTrue()
    }

    private fun diverged(original: String, edited: String): TokenStreamVerdict.Diverged {
        val verdict = TokenStreamGuard.check(original, edited)
        assertThat(verdict).isInstanceOf(TokenStreamVerdict.Diverged::class.java)
        return verdict as TokenStreamVerdict.Diverged
    }

    @Nested
    inner class `Layout-only edits are safe` {

        @Test
        fun `identical text`() {
            assertSafe("def x = 1", "def x = 1")
        }

        @Test
        fun `spaces and indentation between tokens`() {
            assertSafe(
                "class A {\n  def f( x){\nreturn x+1\n }\n}\n",
                "class A {\n    def f(x) {\n        return x + 1\n    }\n}\n",
            )
        }

        @Test
        fun `blank lines are collapsed or added`() {
            assertSafe("def a = 1\n\n\n\ndef b = 2\n", "def a = 1\n\ndef b = 2")
        }

        @Test
        fun `CRLF line endings between statements`() {
            assertSafe("def a = 1\r\ndef b = 2\r\n", "def a = 1\ndef b = 2\n")
        }

        @Test
        fun `line breaks inside parentheses and brackets`() {
            assertSafe("foo(1,\n    2,\n    3)\ndef l = [\n  1,\n  2\n]\n", "foo(1, 2, 3)\ndef l = [1, 2]\n")
        }

        @Test
        fun `comment re-indentation`() {
            assertSafe(
                "class A {\n/**\n   * Doc.\n   */\ndef f() {}\n}\n",
                "class A {\n    /**\n     * Doc.\n     */\n    def f() {}\n}\n",
            )
        }

        @Test
        fun `whitespace inside GString interpolation braces`() {
            assertSafe("def s = \"a \${ x+1 } b\"", "def s = \"a \${x + 1} b\"")
        }

        @Test
        fun `line breaks inserted after an opening brace and before a closing brace`() {
            assertSafe(
                "class FormatTest{def run(){println \"test\"}}\n",
                "class FormatTest {\n    def run() {\n        println \"test\"\n    }\n}\n",
            )
        }

        @Test
        fun `line break after a closure arrow`() {
            assertSafe("def c = { int value -> value * factor }\n", "def c = { int value ->\n    value * factor\n}\n")
        }

        @Test
        fun `comments between a brace and a statement`() {
            assertSafe(
                "def make() { /* c */ new Left(); new Right() }\n",
                "def make() {\n    /* c */\n    new Left();\n    new Right()\n}\n",
            )
        }

        @Test
        fun `line break after a semicolon`() {
            assertSafe("def a = 1; def b = 2\n", "def a = 1;\ndef b = 2\n")
        }

        @Test
        fun `else, catch and finally joined onto the closing brace`() {
            assertSafe(
                "if (x) {\n    a()\n}\nelse {\n    b()\n}\ntry {\n    c()\n}\ncatch (e) {\n}\nfinally {\n}\n",
                "if (x) {\n    a()\n} else {\n    b()\n}\ntry {\n    c()\n} catch (e) {\n} finally {\n}\n",
            )
        }

        @Test
        fun `GString at the end of the file gains a trailing newline`() {
            assertSafe("def s = \"\$a\"", "def s = \"\$a\"\n")
        }

        @Test
        fun `CRLF inside multi-line string literals, which the compiler normalises`() {
            val tq = "\"\"\""
            assertSafe(
                "def s = '''a\r\nb'''\r\ndef r = /x\r\ny/\r\ndef g = $tq\${a}\r\nb$tq\r\n",
                "def s = '''a\nb'''\ndef r = /x\ny/\ndef g = $tq\${a}\nb$tq\n",
            )
        }

        @Test
        fun `shebang kept while the body is re-indented`() {
            assertSafe("#!/usr/bin/env groovy\n\n\n  println 1\n", "#!/usr/bin/env groovy\n\nprintln 1\n")
        }
    }

    @Nested
    inner class `Meaning-changing edits are unsafe` {

        @Test
        fun `dropped spaces merge tokens around the as operator (#1523)`() {
            val divergence = diverged(
                "def y = x as BigDecimal\n",
                "def y = xasBigDecimal\n",
            )

            assertThat(divergence.expected).isEqualTo(SignificantToken("Identifier", "x", line = 1, column = 9))
            assertThat(divergence.actual?.text).isEqualTo("xasBigDecimal")
            assertThat(divergence.describe()).contains("'x' at 1:9").contains("'xasBigDecimal' at 1:9")
        }

        @Test
        fun `dropped token`() {
            val divergence = diverged("def x = a + b\n", "def x = a b\n")
            assertThat(divergence.expected?.text).isEqualTo("+")
            assertThat(divergence.actual?.text).isEqualTo("b")
        }

        @Test
        fun `changed token`() {
            val divergence = diverged("def x = 1\n", "def x = 2\n")
            assertThat(divergence.expected?.text).isEqualTo("1")
            assertThat(divergence.actual?.text).isEqualTo("2")
        }

        @Test
        fun `appended token reports the end of the original`() {
            val divergence = diverged("def x = 1", "def x = 1 + 2")
            assertThat(divergence.expected).isNull()
            assertThat(divergence.actual?.text).isEqualTo("+")
        }

        @Test
        fun `truncated edit reports the end of the edited text`() {
            val divergence = diverged("def x = 1\nprintln x\n", "def x = 1\n")
            assertThat(divergence.expected?.kind).isEqualTo(SignificantToken.SEPARATOR)
            assertThat(divergence.actual).isNull()
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(
            delimiter = '|',
            quoteCharacter = '`',
            value = [
                "double-quoted | def a = \"x    y\" | def a = \"x y\"",
                "single-quoted | def b = 'p     q' | def b = 'p q'",
                "slashy        | def c = /r    s/  | def c = /r s/",
                "dollar-slashy | def d = \$/r    s/\$ | def d = \$/r s/\$",
                "GString text  | def e = \"a  \${x}  b\" | def e = \"a \${x} b\"",
            ],
        )
        fun `whitespace inside a string literal (#1524)`(kind: String, original: String, edited: String) {
            val divergence = diverged(original.trim(), edited.trim())
            assertThat(divergence.expected?.text).describedAs(kind).isNotEqualTo(divergence.actual?.text)
        }

        @Test
        fun `whitespace inside a triple-quoted string (#1524)`() {
            val original = "def s = \"\"\"\\\n    |a:    1\n    \"\"\".stripMargin()\n"
            val edited = "def s = \"\"\"\\\n    |a: 1\n    \"\"\".stripMargin()\n"
            diverged(original, edited)
        }

        @Test
        fun `indentation inside a triple-quoted string`() {
            diverged("def s = '''\n  a\n'''\n", "def s = '''\n    a\n'''\n")
        }

        @Test
        fun `whitespace inside a multiline slashy string`() {
            diverged("def r = /(?x)\n    \\d{3}      # area\n/\n", "def r = /(?x)\n    \\d{3} # area\n/\n")
        }

        @Test
        fun `joining two statements onto one line`() {
            val divergence = diverged("return\nx\n", "return x\n")
            assertThat(divergence.expected?.kind).isEqualTo(SignificantToken.SEPARATOR)
        }

        @Test
        fun `joining a closure onto the call on the previous line`() {
            // `foo {}` then `(x)` on its own line is two statements; `foo {} (x)` calls the result.
            val divergence = diverged("foo {}\n(x)\n", "foo {} (x)\n")
            assertThat(divergence.expected?.kind).isEqualTo(SignificantToken.SEPARATOR)
        }

        @Test
        fun `joining a block onto the call on the previous line`() {
            diverged("foo()\n{ println 1 }\n", "foo() { println 1 }\n")
        }

        @Test
        fun `moving a case arrow onto its own line`() {
            val original = "def r = switch (x) {\n    case 1 -> 'a'\n    default -> 'b'\n}\n"
            val edited = "def r = switch (x) {\n    case 1\n    -> 'a'\n    default -> 'b'\n}\n"
            assertThat(TokenStreamGuard.check(original, edited).isSafe).isFalse()
        }

        @Test
        fun `comment text`() {
            val divergence = diverged("// keep   this\ndef x = 1\n", "// keep this\ndef x = 1\n")
            assertThat(divergence.expected?.kind).isEqualTo(SignificantToken.COMMENT)
        }

        @Test
        fun `removed comment`() {
            diverged("def x = 1 // why\n", "def x = 1\n")
        }

        @Test
        fun `comment inside parentheses`() {
            diverged("foo(1, /* a */ 2)\n", "foo(1, /* b */ 2)\n")
        }

        @Test
        fun `changed second shebang line`() {
            diverged("#!/a\n#!b\nprintln 1\n", "#!/a\n#!c\nprintln 1\n")
        }

        @Test
        fun `changed shebang`() {
            val divergence = diverged("#!/usr/bin/env groovy\nprintln 1\n", "#!/usr/bin/groovy\nprintln 1\n")
            assertThat(divergence.expected?.kind).isEqualTo(SignificantToken.SHEBANG)
        }
    }

    @Nested
    inner class `Lexer errors` {

        @Test
        fun `edit that introduces an unterminated string`() {
            val verdict = TokenStreamGuard.check("def s = 'a'\n", "def s = 'a\n")
            assertThat(verdict).isInstanceOf(TokenStreamVerdict.IntroducedLexError::class.java)
            assertThat(verdict.isSafe).isFalse()
        }

        @Test
        fun `original that cannot be lexed is never vouched for`() {
            val verdict = TokenStreamGuard.check("def s = 'a\n", "def s =  'a\n")
            assertThat(verdict).isInstanceOf(TokenStreamVerdict.OriginalUnverifiable::class.java)
            assertThat(verdict.isSafe).isFalse()
        }
    }
}
