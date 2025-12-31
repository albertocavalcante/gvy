package com.github.albertocavalcante.groovyparser.ast.body

import com.github.albertocavalcante.groovyparser.GroovyParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ClassDeclarationTest {

    private val parser = GroovyParser()

    @Test
    fun `parse simple class`() {
        val result = parser.parse("class Foo {}")

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        assertThat(classDecl.name).isEqualTo("Foo")
        assertThat(classDecl.isInterface).isFalse()
        assertThat(classDecl.isEnum).isFalse()
    }

    @Test
    fun `parse interface`() {
        val result = parser.parse("interface Foo {}")

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        assertThat(classDecl.name).isEqualTo("Foo")
        assertThat(classDecl.isInterface).isTrue()
    }

    @Test
    fun `parse enum`() {
        val result = parser.parse("enum Color { RED, GREEN, BLUE }")

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        assertThat(classDecl.name).isEqualTo("Color")
        assertThat(classDecl.isEnum).isTrue()
    }

    @Test
    fun `parse class with superclass`() {
        val result = parser.parse("class Foo extends Bar {}")

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        assertThat(classDecl.superClass).isEqualTo("Bar")
    }

    @Test
    fun `parse class with interfaces`() {
        val result = parser.parse("class Foo implements Runnable, Serializable {}")

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        // At CONVERSION phase, interface names may not be fully resolved
        assertThat(classDecl.implementedTypes).contains("Runnable", "Serializable")
    }

    @Test
    fun `class declaration toString`() {
        val classDecl = ClassDeclaration("Foo")

        assertThat(classDecl.toString()).isEqualTo("class Foo")
    }

    @Test
    fun `interface declaration toString`() {
        val classDecl = ClassDeclaration("Foo", isInterface = true)

        assertThat(classDecl.toString()).isEqualTo("interface Foo")
    }

    @Test
    fun `enum declaration toString`() {
        val classDecl = ClassDeclaration("Color", isEnum = true)

        assertThat(classDecl.toString()).isEqualTo("enum Color")
    }

    @Test
    fun `parse class with methods`() {
        val result = parser.parse(
            """
            class Foo {
                void bar() {}
                String getName() { return "test" }
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        assertThat(classDecl.methods).hasSize(2)
    }

    @Test
    fun `parse class with fields`() {
        val result = parser.parse(
            """
            class Foo {
                String name
                int count = 0
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        assertThat(classDecl.fields).hasSize(2)
    }

    @Test
    fun `parse class with constructor`() {
        val result = parser.parse(
            """
            class Foo {
                Foo(String name) {}
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        assertThat(classDecl.constructors).hasSize(1)
    }
}
