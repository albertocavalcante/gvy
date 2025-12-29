package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.StaticGroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AnnotationTest {

    @Test
    fun `parse class with annotation`() {
        val unit = StaticGroovyParser.parse(
            """
            @ToString
            class Foo {
                String name
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        assertThat(classDecl.annotations).hasSize(1)
        assertThat(classDecl.annotations[0].name).contains("ToString")
    }

    @Test
    fun `parse method with annotation`() {
        val unit = StaticGroovyParser.parse(
            """
            class Foo {
                @Override
                String toString() {
                    return "Foo"
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods.find { it.name == "toString" }
        assertThat(method).isNotNull
        assertThat(method!!.annotations).hasSize(1)
        assertThat(method.annotations[0].name).contains("Override")
    }

    @Test
    fun `parse NonCPS annotation for Jenkins`() {
        val unit = StaticGroovyParser.parse(
            """
            import com.cloudbees.groovy.cps.NonCPS

            class Pipeline {
                @NonCPS
                String helper() {
                    return Thread.currentThread().name
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods.find { it.name == "helper" }
        assertThat(method).isNotNull
        assertThat(method!!.hasAnnotation("NonCPS")).isTrue()
        assertThat(method.isNonCps).isTrue()
    }

    @Test
    fun `parse field with annotation`() {
        val unit = StaticGroovyParser.parse(
            """
            import groovy.transform.Field

            class Script {
                @Field
                String scriptName = "test"
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val field = classDecl.fields.find { it.name == "scriptName" }
        assertThat(field).isNotNull
        assertThat(field!!.hasAnnotation("Field")).isTrue()
    }

    @Test
    fun `parse annotation with value`() {
        val unit = StaticGroovyParser.parse(
            """
            @SuppressWarnings("unchecked")
            class Foo {
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        assertThat(classDecl.annotations).hasSize(1)
        val annotation = classDecl.annotations[0]
        assertThat(annotation.name).contains("SuppressWarnings")
    }

    @Test
    fun `parse multiple annotations`() {
        val unit = StaticGroovyParser.parse(
            """
            @ToString
            @EqualsAndHashCode
            class Foo {
                String name
                int age
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        assertThat(classDecl.annotations).hasSize(2)
    }

    @Test
    fun `hasAnnotation returns false when not present`() {
        val unit = StaticGroovyParser.parse(
            """
            class Foo {
                void bar() {}
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        assertThat(classDecl.hasAnnotation("ToString")).isFalse()
        assertThat(classDecl.isNonCps).isFalse()
    }
}
