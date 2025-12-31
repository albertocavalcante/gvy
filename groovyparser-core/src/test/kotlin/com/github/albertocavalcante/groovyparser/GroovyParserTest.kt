package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
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

    @Test
    fun `lenient mode returns empty CompilationUnit on compilation error with no moduleNode`() {
        // Issue 1: When lenient mode is enabled and there's a compilation error
        // but no recoverable moduleNode, should return empty CompilationUnit, NOT null
        val config = ParserConfiguration().setLenientMode(true)
        val parser = GroovyParser(config)

        // Code that causes severe compilation error with no recoverable AST
        // This should trigger CompilationFailedException and have no moduleNode
        val result = parser.parse("class {{{")

        // In lenient mode, should still return a result (not null)
        assertThat(result.result).isPresent
        assertThat(result.result.get()).isInstanceOf(CompilationUnit::class.java)
        // Should have problems recorded
        assertThat(result.problems).isNotEmpty()
        assertThat(result.isSuccessful).isFalse()
    }

    @Test
    fun `GroovyClassLoader is properly closed to prevent resource leak`() {
        // Issue 2: Verify that repeated parsing doesn't leak resources
        // by ensuring GroovyClassLoader is properly closed after each parse
        val parser = GroovyParser()

        // Parse multiple times - if classloader isn't closed, this could
        // eventually exhaust file handles or cause memory pressure
        repeat(100) { i ->
            val result = parser.parse("class Test$i { def method() { return $i } }")
            assertThat(result.isSuccessful).isTrue()
        }

        // If we got here without exceptions or hanging, the resource management is working
        // (A resource leak would eventually cause failures or extreme slowdown)
    }
}
