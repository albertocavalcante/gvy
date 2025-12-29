package com.github.albertocavalcante.groovyparser.ast.body

import com.github.albertocavalcante.groovyparser.GroovyParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FieldDeclarationTest {

    private val parser = GroovyParser()

    @Test
    fun `parse field with type`() {
        val result = parser.parse(
            """
            class Foo {
                String name
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        assertThat(classDecl.fields).isNotEmpty()
        val field = classDecl.fields[0]

        assertThat(field.name).isEqualTo("name")
        // At CONVERSION phase, types may not be fully resolved
        assertThat(field.type).isIn("String", "java.lang.String")
    }

    @Test
    fun `parse field with initializer`() {
        val result = parser.parse(
            """
            class Foo {
                int count = 42
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val field = classDecl.fields[0]

        assertThat(field.name).isEqualTo("count")
        assertThat(field.hasInitializer).isTrue()
    }

    @Test
    fun `parse static field`() {
        val result = parser.parse(
            """
            class Foo {
                static String DEFAULT = "default"
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val field = classDecl.fields[0]

        assertThat(field.isStatic).isTrue()
    }

    @Test
    fun `field declaration toString`() {
        val field = FieldDeclaration("name", "String")

        assertThat(field.toString()).contains("name")
        assertThat(field.toString()).contains("String")
    }
}
