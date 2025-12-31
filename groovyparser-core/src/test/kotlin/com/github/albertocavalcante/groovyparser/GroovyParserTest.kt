package com.github.albertocavalcante.groovyparser

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GroovyParserTest {

    @Test
    fun `parse empty string returns successful result with empty compilation unit`() {
        val parser = GroovyParser()
        val result = parser.parse("")

        assertThat(result.isSuccessful).isTrue()
        assertThat(result.result).isPresent
    }

    @Test
    fun `parse simple class returns compilation unit with class`() {
        val parser = GroovyParser()
        val result = parser.parse("class Foo {}")

        assertThat(result.isSuccessful).isTrue()
        val unit = result.result.get()
        assertThat(unit.types).hasSize(1)
        assertThat(unit.types[0].name).isEqualTo("Foo")
    }

    @Test
    fun `parse invalid syntax returns result with problems`() {
        val parser = GroovyParser()
        val result = parser.parse("class { invalid }")

        assertThat(result.isSuccessful).isFalse()
        assertThat(result.problems).isNotEmpty()
    }

    @Test
    fun `parser with configuration uses configured settings`() {
        val config = ParserConfiguration()
            .setLanguageLevel(GroovyLanguageLevel.GROOVY_4_0)
            .setTabSize(4)

        val parser = GroovyParser(config)

        assertThat(parser.configuration.languageLevel).isEqualTo(GroovyLanguageLevel.GROOVY_4_0)
        assertThat(parser.configuration.tabSize).isEqualTo(4)
    }

    @Test
    fun `parse with package declaration`() {
        val parser = GroovyParser()
        val result = parser.parse(
            """
            package com.example
            
            class Foo {}
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val unit = result.result.get()
        assertThat(unit.packageDeclaration).isPresent
        assertThat(unit.packageDeclaration.get().name).isEqualTo("com.example")
    }

    @Test
    fun `parse with imports`() {
        val parser = GroovyParser()
        val result = parser.parse(
            """
            import java.util.List
            import java.util.Map
            
            class Foo {}
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val unit = result.result.get()
        assertThat(unit.imports).hasSize(2)
    }

    @Test
    fun `parse script without class`() {
        val parser = GroovyParser()
        val result = parser.parse(
            """
            println "Hello, World!"
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
    }

    @Test
    fun `parser can be reused for multiple parses`() {
        val parser = GroovyParser()

        val result1 = parser.parse("class A {}")
        val result2 = parser.parse("class B {}")

        assertThat(result1.isSuccessful).isTrue()
        assertThat(result2.isSuccessful).isTrue()
        assertThat(result1.result.get().types[0].name).isEqualTo("A")
        assertThat(result2.result.get().types[0].name).isEqualTo("B")
    }
}
