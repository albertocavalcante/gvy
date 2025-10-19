package com.github.albertocavalcante.groovylsp.formatter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class OpenRewriteFormatterTest {

    private val formatter = OpenRewriteFormatter()

    @Test
    fun `should format simple script with bad indentation`() {
        val unformatted = """
            def x = 1
              def y = 2
        """.trimIndent()
        val expected = """
            def x = 1
            def y = 2
        """.trimIndent()

        val result = formatter.format(unformatted)

        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `should not change already formatted script`() {
        val formatted = """
            def x = 1
            def y = 2
        """.trimIndent()

        val result = formatter.format(formatted)

        assertThat(result).isEqualTo(formatted)
    }

    @Test
    fun `should handle empty string`() {
        val result = formatter.format("")
        assertThat(result).isEmpty()
    }

    @Test
    fun `should format script with closures`() {
        val unformatted = """
            def list = [1, 2, 3]
            list.each {  it ->
              println it
            }
        """.trimIndent()
        val expected = """
            def list = [1, 2, 3]
            list.each { it ->
                println it
            }
        """.trimIndent()

        val result = formatter.format(unformatted)

        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `should not change script with syntax errors`() {
        val invalidScript = "def x = "
        val result = formatter.format(invalidScript)
        assertThat(result).isEqualTo(invalidScript)
    }

    @Test
    fun `should collapse redundant inline spaces`() {
        val unformatted = """
            def total  =  41
            println  total
        """.trimIndent()
        val expected = """
            def total = 41
            println total
        """.trimIndent()

        val result = formatter.format(unformatted)

        assertThat(result).isEqualTo(expected)
    }

    @Disabled("OpenRewrite formatter does not tighten method invocation spacing yet.")
    @Test
    fun `should format method call spacing`() {
        val unformatted = """
            println(  "hi" )
        """.trimIndent()
        val expected = """
            println("hi")
        """.trimIndent()

        val result = formatter.format(unformatted)

        assertThat(result).isEqualTo(expected)
    }

    @Disabled("OpenRewrite formatter keeps operators tight without spaces; needs follow-up recipe.")
    @Test
    fun `should format arithmetic expressions`() {
        val unformatted = """
            def sum=1+2*3
        """.trimIndent()
        val expected = """
            def sum = 1 + 2 * 3
        """.trimIndent()

        val result = formatter.format(unformatted)

        assertThat(result).isEqualTo(expected)
    }

    @Disabled("OpenRewrite formatter leaves whitespace around map entries unchanged; document limitation.")
    @Test
    fun `should format map literals`() {
        val unformatted = """
            def map = [ 'a' :1 ,b:2, c : 3]
        """.trimIndent()
        val expected = """
            def map = ['a': 1, b: 2, c: 3]
        """.trimIndent()

        val result = formatter.format(unformatted)

        assertThat(result).isEqualTo(expected)
    }

    @Disabled("OpenRewrite formatter currently omits spacing around parentheses/braces; track for upstream fix.")
    @Test
    fun `should format nested control structures`() {
        val unformatted = """
            class Greeter {
            def greet(String name){
            if(name){
              println  "Hello ${'$'}name"
            }
            }
            }
        """.trimIndent()
        val expected = """
            class Greeter {
                def greet(String name) {
                    if (name) {
                        println "Hello ${'$'}name"
                    }
                }
            }
        """.trimIndent()

        val result = formatter.format(unformatted)

        assertThat(result).isEqualTo(expected)
    }

    @Disabled("OpenRewrite formatter omits spaces around for-loop parentheses/braces; upstream fix needed.")
    @Test
    fun `should format for loop spacing`() {
        val unformatted = """
            for(item in [1,2,3]){
            println  item
            }
        """.trimIndent()
        val expected = """
            for (item in [1, 2, 3]) {
                println item
            }
        """.trimIndent()

        val result = formatter.format(unformatted)

        assertThat(result).isEqualTo(expected)
    }
}
