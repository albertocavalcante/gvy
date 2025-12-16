package com.github.albertocavalcante.groovyformatter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class OpenRewriteFormatterTest {

    private val formatter = OpenRewriteFormatter()

    /**
     * Helper to make test assertions more concise and readable.
     */
    private fun assertFormatsTo(input: String, expected: String) {
        assertThat(formatter.format(input))
            .withFailMessage { "Formatting did not produce expected output" }
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
            assertFormatsTo(
                input = """
                    def list = [1, 2, 3]
                    list.each {  it ->
                      println it
                    }
                """.trimIndent(),
                expected = """
                    def list = [1, 2, 3]
                    list.each { it ->
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
}
