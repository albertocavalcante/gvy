package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StaticGroovyParserTest {

    @Test
    fun `parse simple class`() {
        val unit = StaticGroovyParser.parse("class Foo {}")

        assertThat(unit.types).hasSize(1)
        assertThat(unit.types[0].name).isEqualTo("Foo")
    }

    @Test
    fun `parse class with package`() {
        val unit = StaticGroovyParser.parse(
            """
            package com.example
            class Foo {}
            """.trimIndent(),
        )

        assertThat(unit.packageDeclaration).isPresent
        assertThat(unit.packageDeclaration.get().name).isEqualTo("com.example")
    }

    @Test
    fun `parse throws exception on syntax error`() {
        assertThatThrownBy {
            StaticGroovyParser.parse("class { invalid }")
        }.isInstanceOf(ParseProblemException::class.java)
    }

    @Test
    fun `getConfiguration returns current configuration`() {
        val config = StaticGroovyParser.getConfiguration()

        assertThat(config).isNotNull
        assertThat(config.languageLevel).isEqualTo(GroovyLanguageLevel.POPULAR)
    }

    @Test
    fun `setConfiguration changes the configuration`() {
        val originalConfig = StaticGroovyParser.getConfiguration()
        try {
            val newConfig = ParserConfiguration()
                .setLanguageLevel(GroovyLanguageLevel.GROOVY_2_4)

            StaticGroovyParser.setConfiguration(newConfig)

            assertThat(StaticGroovyParser.getConfiguration().languageLevel)
                .isEqualTo(GroovyLanguageLevel.GROOVY_2_4)
        } finally {
            // Restore original configuration
            StaticGroovyParser.setConfiguration(originalConfig)
        }
    }

    @Test
    fun `parse multiple classes`() {
        val unit = StaticGroovyParser.parse(
            """
            class Foo {}
            class Bar {}
            """.trimIndent(),
        )

        assertThat(unit.types).hasSize(2)
    }

    @Test
    fun `parse interface`() {
        val unit = StaticGroovyParser.parse("interface Foo {}")

        val type = unit.types[0] as ClassDeclaration
        assertThat(type.isInterface).isTrue()
    }

    @Test
    fun `parse enum`() {
        val unit = StaticGroovyParser.parse("enum Color { RED, GREEN, BLUE }")

        val type = unit.types[0] as ClassDeclaration
        assertThat(type.isEnum).isTrue()
    }

    @Test
    fun `parse script`() {
        val unit = StaticGroovyParser.parse("println 'Hello, World!'")

        assertThat(unit).isNotNull
    }
}
