package com.github.albertocavalcante.gvy.fmt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class OpenRewriteFormatterTest {

    private val formatter = OpenRewriteFormatter()

    /**
     * Helper to make test assertions more concise and readable.
     */
    private fun assertFormatsTo(input: String, expected: String) {
        val actual = formatter.format(input)
        assertThat(actual)
            .withFailMessage { "Formatting did not produce expected output.\nExpected:\n$expected\n\nActual:\n$actual" }
            .isEqualTo(expected)
    }

    @Nested
    inner class `General formatting` {

        @Test
        fun `should format simple script with bad indentation`() {
            assertFormatsTo(
                input = """
                    def x = 1
                      def y = 2
                """.trimIndent(),
                expected = """
                    def x = 1
                    def y = 2
                """.trimIndent(),
            )
        }

        @Test
        fun `should not change already formatted script`() {
            val formatted = """
                def x = 1
                def y = 2
            """.trimIndent()

            assertFormatsTo(formatted, formatted)
        }

        @Test
        fun `should handle empty string`() {
            assertThat(formatter.format("")).isEmpty()
        }

        @Test
        fun `should format script with closures`() {
            // TODO(#393): OpenRewrite 8.70.1 regression - removes space after opening brace in closures.
            //   Expected: list.each { it -> (with space)
            //   Actual: list.each {it -> (no space)
            //   See: https://github.com/albertocavalcante/groovy-devtools/issues/393
            assertFormatsTo(
                input = """
                    def list = [1, 2, 3]
                    list.each {  it ->
                      println it
                    }
                """.trimIndent(),
                expected = """
                    def list = [1, 2, 3]
                    list.each {it ->
                        println it
                    }
                """.trimIndent(),
            )
        }

        @Test
        fun `should not change script with syntax errors`() {
            val invalidScript = "def x = "
            assertFormatsTo(invalidScript, invalidScript)
        }

        @Test
        fun `should collapse redundant inline spaces`() {
            assertFormatsTo(
                input = """
                    def total  =  41
                    println  total
                """.trimIndent(),
                expected = """
                    def total = 41
                    println total
                """.trimIndent(),
            )
        }
    }

    @Nested
    inner class `Known OpenRewrite limitations` {

        @Disabled("OpenRewrite formatter does not tighten method invocation spacing yet.")
        @Test
        fun `should format method call spacing`() {
            assertFormatsTo(
                input = "println(  \"hi\" )",
                expected = "println(\"hi\")",
            )
        }

        @Disabled("OpenRewrite formatter keeps operators tight without spaces; needs follow-up recipe.")
        @Test
        fun `should format arithmetic expressions`() {
            assertFormatsTo(
                input = "def sum=1+2*3",
                expected = "def sum = 1 + 2 * 3",
            )
        }

        @Disabled("OpenRewrite formatter leaves whitespace around map entries unchanged; document limitation.")
        @Test
        fun `should format map literals`() {
            assertFormatsTo(
                input = "def map = [ 'a' :1 ,b:2, c : 3]",
                expected = "def map = ['a': 1, b: 2, c: 3]",
            )
        }

        @Disabled("OpenRewrite formatter currently omits spacing around parentheses/braces; track for upstream fix.")
        @Test
        fun `should format nested control structures`() {
            assertFormatsTo(
                input = """
                    class Greeter {
                    def greet(String name){
                    if(name){
                      println  "Hello ${'$'}name"
                    }
                    }
                    }
                """.trimIndent(),
                expected = """
                    class Greeter {
                        def greet(String name) {
                            if (name) {
                                println "Hello ${'$'}name"
                            }
                        }
                    }
                """.trimIndent(),
            )
        }

        @Disabled("OpenRewrite formatter omits spaces around for-loop parentheses/braces; upstream fix needed.")
        @Test
        fun `should format for loop spacing`() {
            assertFormatsTo(
                input = """
                    for(item in [1,2,3]){
                    println  item
                    }
                """.trimIndent(),
                expected = """
                    for (item in [1, 2, 3]) {
                        println item
                    }
                """.trimIndent(),
            )
        }
    }

    @Nested
    inner class `Shebang handling` {

        @Test
        fun `should preserve standard shebang with imports`() {
            assertFormatsTo(
                input = """
                    #!/usr/bin/env groovy

                    import io.jenkins.infra.InfraConfig
                    import jenkins.scm.api.SCMSource
                    import com.cloudbees.groovy.cps.NonCPS

                    // Method kept for backward compatibility
                    Boolean isRunningOnJenkinsInfra() {
                      return new InfraConfig(env).isRunningOnJenkinsInfra()
                    }
                """.trimIndent(),
                expected = """
                    #!/usr/bin/env groovy

                    import io.jenkins.infra.InfraConfig
                    import jenkins.scm.api.SCMSource
                    import com.cloudbees.groovy.cps.NonCPS

                    // Method kept for backward compatibility
                    Boolean isRunningOnJenkinsInfra() {
                        return new InfraConfig(env).isRunningOnJenkinsInfra()
                    }
                """.trimIndent(),
            )
        }

        @Test
        fun `should normalize shebang with no blank line after it`() {
            assertFormatsTo(
                input = """
                    #!/usr/bin/env groovy
                    import java.util.List

                    def x = 1
                """.trimIndent(),
                expected = """
                    #!/usr/bin/env groovy

                    import java.util.List

                    def x = 1
                """.trimIndent(),
            )
        }

        @Test
        fun `should preserve shebang with direct path`() {
            assertFormatsTo(
                input = """
                    #!/usr/bin/groovy

                    println "Hello"
                """.trimIndent(),
                expected = """
                    #!/usr/bin/groovy

                    println "Hello"
                """.trimIndent(),
            )
        }

        @Test
        fun `should preserve shortened shebang format`() {
            assertFormatsTo(
                input = """
                    #!groovy

                    def x = 1
                """.trimIndent(),
                expected = """
                    #!groovy

                    def x = 1
                """.trimIndent(),
            )
        }

        @Test
        fun `should handle script with only shebang`() {
            assertFormatsTo(
                input = "#!/usr/bin/env groovy",
                expected = "#!/usr/bin/env groovy",
            )
        }

        @Test
        fun `should handle script with shebang and whitespace only`() {
            assertFormatsTo(
                input = "#!/usr/bin/env groovy\n\n\n",
                expected = "#!/usr/bin/env groovy",
            )
        }

        @Test
        fun `should not treat comment as shebang`() {
            val script = """
                // This is a comment
                #!/usr/bin/env groovy

                def x = 1
            """.trimIndent()

            // Comment before #! means it's not a shebang, so it stays as-is
            assertFormatsTo(script, script)
        }

        @Test
        fun `should handle script without shebang`() {
            val script = """
                import java.util.List

                def x = 1
            """.trimIndent()

            assertFormatsTo(script, script)
        }

        @Test
        fun `should normalize Windows CRLF line endings to Unix`() {
            assertFormatsTo(
                input = "#!/usr/bin/env groovy\r\n\r\nimport java.util.List\r\n\r\ndef x = 1\r\n",
                expected = "#!/usr/bin/env groovy\n\nimport java.util.List\n\ndef x = 1\n",
            )
        }

        @Test
        fun `should handle shebang with extra spaces after hash-bang`() {
            assertFormatsTo(
                input = """
                    #!  /usr/bin/env groovy

                    def x = 1
                """.trimIndent(),
                expected = """
                    #!  /usr/bin/env groovy

                    def x = 1
                """.trimIndent(),
            )
        }

        @Test
        fun `should normalize multiple blank lines after shebang to single blank line`() {
            assertFormatsTo(
                input = """
                    #!/usr/bin/env groovy



                    import java.util.List
                """.trimIndent(),
                expected = """
                    #!/usr/bin/env groovy

                    import java.util.List
                """.trimIndent(),
            )
        }
    }

    @Nested
    inner class `Slashy String Handling` {

        @ParameterizedTest
        @ValueSource(
            strings = [
                """
                def regex = /\d+\.\d+/
                println(regex)
                """,
                """
                def pattern = /[a-zA-Z0-9_\-\.]+@[a-zA-Z0-9_\-\.]+\.[a-zA-Z]{2,5}/
                def result = email ==~ pattern
                """,
                """
                def path = /\/usr\/local\/bin/
                println(path)
                """,
                """
                def regex = /\$\{[a-z]+\}/
                println(regex)
                """,
            ],
        )
        fun `should preserve slashy strings that do not require formatting`(input: String) {
            val code = input.trimIndent()
            assertFormatsTo(code, code)
        }

        @Test
        fun `should format slashy string in method call`() {
            val input = """
                def matches = text.findAll(  /pattern\d+/  )
            """.trimIndent()

            val expected = """
                def matches = text.findAll(/pattern\d+/)
            """.trimIndent()

            assertFormatsTo(input, expected)
        }

        @Test
        fun `should preserve multiline slashy string`() {
            val input = """
                def regex = /(?x)
                    \d{3}      # area code
                    -          # separator
                    \d{4}      # number
                /
            """.trimIndent()

            // Whitespace inside a slashy string is part of its value (#1524).
            assertFormatsTo(input, input)
        }
    }

    /**
     * Asserts on the formatter's own output, before [TokenStreamGuard] can refuse it, so a
     * damaging transformation shows up as a wrong result instead of an unchanged document.
     */
    @Nested
    inner class `Raw output keeps string and comment text` {

        private fun assertRawFormatsTo(input: String, expected: String) {
            val actual = formatter.formatUnchecked(input)
            assertThat(actual)
                .withFailMessage { "Raw formatter output differs.\nExpected:\n$expected\n\nActual:\n$actual" }
                .isEqualTo(expected)
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "def a = \"x    y\"",
                "def b = 'p     q'",
                "def c = /r    s/",
                "def d = $/t    u/$",
                "def e = \"\${a}    \${b}  !\"",
                "def f = '''g    h'''",
                "def g = \"\"\"\\\n    |a:    1\n    |bb:   2\n    \"\"\".stripMargin()",
            ],
        )
        fun `keeps runs of spaces inside string literals (#1524)`(input: String) {
            assertRawFormatsTo(input, input)
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "// See:   #1524\ndef x = 1",
                "/* a:    1\n * bb:   2 */\ndef x = 1",
                "/**\n * @param a   the a\n */\ndef f(a) {\n    a\n}",
            ],
        )
        fun `keeps runs of spaces inside comments (#1524)`(input: String) {
            assertRawFormatsTo(input, input)
        }

        /** Collapsing on the LST must still produce single spaces where `AutoFormat` splits a run (#1524). */
        @ParameterizedTest
        @ValueSource(
            strings = [
                "def f(String s, x, y) {\n    def z = 1\n}",
                "def f(Map params = [:], Closure body) {\n    body()\n}",
                "private static final int A = 1",
                "for (String s : list) {\n    println s\n}",
                "try {\n    f()\n} catch (Exception e) {\n    println e\n}",
                "try {\n    f()\n} catch (e) {\n    println e\n}",
                "for (x in list) {\n    println x\n}",
                "def a = 1, b = 2",
                "int a, b",
                "String a = 'x', b",
            ],
        )
        fun `keeps single spaces in declarations (#1524)`(input: String) {
            assertRawFormatsTo(input, input)
        }

        @Test
        fun `keeps the spaces before a trailing comment (#1524)`() {
            val input = "def x = 1    // aligned\ndef yy = 2  /* aligned */\nprintln x"
            assertRawFormatsTo(input, input)
        }

        @Test
        fun `still collapses redundant spaces between tokens next to a spaced-out string (#1524)`() {
            assertRawFormatsTo(
                input = "def a  =  \"x    y\"\nprintln  a",
                expected = "def a = \"x    y\"\nprintln a",
            )
        }
    }

    /**
     * The formatter must never change program meaning (Quality Bar: Safe). Each case reproduces a
     * damaging edit from an issue; the result must keep the code intact, whether the formatter
     * handles the construct or refuses to format the document.
     */
    @Nested
    inner class `Program meaning is preserved` {

        @Test
        fun `keeps the spaces around the as operator (#1523)`() {
            val input = """
                class A {
                    def f(x) {
                        def y = x as BigDecimal
                        new Date(x as long)
                    }
                }
            """.trimIndent()

            assertThat(formatter.format(input))
                .contains("def y = x as BigDecimal")
                .contains("new Date(x as long)")
        }

        @Test
        fun `keeps runs of spaces inside string literals (#1524)`() {
            val tripleQuote = "\"\"\""
            val input = listOf(
                "def a = \"x    y\"",
                "def b = 'p     q'",
                "def c = /r    s/",
                "def s = $tripleQuote\\",
                "    |a:    1",
                "    |bb:   2",
                "    $tripleQuote.stripMargin()",
                "println a",
            ).joinToString("\n")

            assertThat(formatter.format(input))
                .contains("\"x    y\"")
                .contains("'p     q'")
                .contains("/r    s/")
                .contains("|a:    1")
                .contains("|bb:   2")
        }

        @Test
        fun `formats a document with spaced-out strings and comments instead of refusing it (#1524)`() {
            assertFormatsTo(
                input = listOf(
                    "def a  =  \"x    y\"",
                    "if(a){",
                    "println  'p     q' /* a:   1 */",
                    "}",
                    "// See:   #1524",
                ).joinToString("\n"),
                expected = listOf(
                    "def a = \"x    y\"",
                    "if (a) {",
                    "    println 'p     q' /* a:   1 */",
                    "}",
                    "// See:   #1524",
                ).joinToString("\n"),
            )
        }

        @Test
        fun `still formats a one-line class onto several lines`() {
            assertFormatsTo(
                input = "class A{def f(){1}}",
                expected = "class A {\n    def f() {\n        1\n    }\n}",
            )
        }

        @Test
        fun `still formats a closure with an arrow`() {
            assertFormatsTo(input = "def c = {  x->x * 2 }\nc(1)", expected = "def c = {x ->\n    x * 2\n}\nc(1)")
        }

        @Test
        fun `still formats a block whose first statement follows a comment`() {
            assertFormatsTo(
                input = "def make() { /* c */ new Date();  1 }",
                expected = "def make() { /* c */\n    new Date();\n    1\n}",
            )
        }

        @Test
        fun `still formats layout when the token stream is unchanged`() {
            assertFormatsTo(
                input = "def x = foo(  1,2 )\nif(x){\nprintln x\n}",
                expected = "def x = foo(1, 2)\nif (x) {\n    println x\n}",
            )
        }
    }
}
