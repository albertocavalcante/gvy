package com.github.albertocavalcante.groovyparser.ast.body

import com.github.albertocavalcante.groovyparser.GroovyParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MethodDeclarationTest {

    private val parser = GroovyParser()

    @Test
    fun `parse method with no parameters`() {
        val result = parser.parse(
            """
            class Foo {
                void bar() {}
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods[0]

        assertThat(method.name).isEqualTo("bar")
        assertThat(method.parameters).isEmpty()
    }

    @Test
    fun `parse method with parameters`() {
        val result = parser.parse(
            """
            class Foo {
                void bar(String name, int count) {}
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods[0]

        assertThat(method.name).isEqualTo("bar")
        assertThat(method.parameters).hasSize(2)
        assertThat(method.parameters[0].name).isEqualTo("name")
        assertThat(method.parameters[1].name).isEqualTo("count")
    }

    @Test
    fun `parse method with return type`() {
        val result = parser.parse(
            """
            class Foo {
                String getName() { return "test" }
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods[0]

        assertThat(method.name).isEqualTo("getName")
        // At CONVERSION phase, types may not be fully resolved
        assertThat(method.returnType).isIn("String", "java.lang.String")
    }

    @Test
    fun `parse method with void return type`() {
        val result = parser.parse(
            """
            class Foo {
                void doSomething() {}
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods[0]

        assertThat(method.returnType).isEqualTo("void")
    }

    @Test
    fun `parse static method`() {
        val result = parser.parse(
            """
            class Foo {
                static void bar() {}
            }
            """.trimIndent(),
        )

        assertThat(result.isSuccessful).isTrue()
        val classDecl = result.result.get().types[0] as ClassDeclaration
        val method = classDecl.methods[0]

        assertThat(method.isStatic).isTrue()
    }

    @Test
    fun `method declaration toString`() {
        val method = MethodDeclaration("bar", "void")

        assertThat(method.toString()).contains("bar")
    }
}
