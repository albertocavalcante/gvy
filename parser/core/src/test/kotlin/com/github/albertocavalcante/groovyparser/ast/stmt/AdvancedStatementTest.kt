package com.github.albertocavalcante.groovyparser.ast.stmt

import com.github.albertocavalcante.groovyparser.StaticGroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AdvancedStatementTest {

    @Test
    fun `parse try-catch statement`() {
        val unit = StaticGroovyParser.parse(
            """
            class Foo {
                void bar() {
                    try {
                        println "try"
                    } catch (Exception e) {
                        println "catch"
                    }
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement

        assertThat(block.statements).hasSize(1)
        val tryCatch = block.statements[0] as TryCatchStatement
        assertThat(tryCatch.catchClauses).hasSize(1)
        assertThat(tryCatch.catchClauses[0].parameter.type).contains("Exception")
    }

    @Test
    fun `parse try-catch-finally statement`() {
        val unit = StaticGroovyParser.parse(
            """
            class Foo {
                void bar() {
                    try {
                        println "try"
                    } catch (Exception e) {
                        println "catch"
                    } finally {
                        println "finally"
                    }
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement

        val tryCatch = block.statements[0] as TryCatchStatement
        assertThat(tryCatch.finallyBlock).isNotNull
    }

    @Test
    fun `parse switch statement`() {
        val unit = StaticGroovyParser.parse(
            """
            class Foo {
                void bar(int x) {
                    switch (x) {
                        case 1:
                            println "one"
                            break
                        case 2:
                            println "two"
                            break
                        default:
                            println "other"
                    }
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement

        val switch = block.statements[0] as SwitchStatement
        assertThat(switch.cases).hasSize(2)
        assertThat(switch.defaultCase).isNotNull
    }

    @Test
    fun `parse throw statement`() {
        val unit = StaticGroovyParser.parse(
            """
            class Foo {
                void bar() {
                    throw new RuntimeException("error")
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement

        val throwStmt = block.statements[0] as ThrowStatement
        assertThat(throwStmt.expression).isNotNull
    }

    @Test
    fun `parse assert statement`() {
        val unit = StaticGroovyParser.parse(
            """
            class Foo {
                void bar(int x) {
                    assert x > 0
                    assert x < 100 : "x must be less than 100"
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement

        assertThat(block.statements).hasSize(2)
        val assert1 = block.statements[0] as AssertStatement
        assertThat(assert1.message).isNull()

        val assert2 = block.statements[1] as AssertStatement
        assertThat(assert2.message).isNotNull
    }

    @Test
    fun `parse break and continue statements`() {
        val unit = StaticGroovyParser.parse(
            """
            class Foo {
                void bar() {
                    for (i in 1..10) {
                        if (i == 5) break
                        if (i % 2 == 0) continue
                        println i
                    }
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val block = method.body as BlockStatement
        val forStmt = block.statements[0] as ForStatement
        val forBody = forStmt.body as BlockStatement

        // The for body should contain if statements with break/continue
        assertThat(forBody.statements.size).isGreaterThan(0)
    }
}
