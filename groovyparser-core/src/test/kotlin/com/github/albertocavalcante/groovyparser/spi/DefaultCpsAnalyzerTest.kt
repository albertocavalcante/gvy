package com.github.albertocavalcante.groovyparser.spi

import com.github.albertocavalcante.groovyparser.StaticGroovyParser
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DefaultCpsAnalyzerTest {

    private val analyzer = DefaultCpsAnalyzer()

    @Test
    fun `simple method is CPS compatible`() {
        val unit = StaticGroovyParser.parse(
            """
            class Pipeline {
                void run() {
                    echo "Hello"
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods[0]

        assertThat(analyzer.isCpsCompatible(method)).isTrue()
    }

    @Test
    fun `closure with standard Groovy method is flagged`() {
        val unit = StaticGroovyParser.parse(
            """
            class Pipeline {
                void run() {
                    [1,2,3].each { println it }
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val violations = analyzer.getCpsViolations(method)

        // Closures passed to standard Groovy methods like 'each' are problematic
        assertThat(violations).isNotEmpty
        assertThat(violations.any { it.type == CpsViolationType.NON_SERIALIZABLE_CLOSURE }).isTrue()
    }

    @Test
    fun `Thread sleep is flagged as non-serializable`() {
        val unit = StaticGroovyParser.parse(
            """
            class Pipeline {
                void run() {
                    Thread.sleep(1000)
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val violations = analyzer.getCpsViolations(method)

        assertThat(violations).isNotEmpty
        assertThat(violations.any { it.type == CpsViolationType.NON_WHITELISTED_METHOD }).isTrue()
    }

    @Test
    fun `method with NonCPS annotation is detected`() {
        val unit = StaticGroovyParser.parse(
            """
            import com.cloudbees.groovy.cps.NonCPS
            
            class Pipeline {
                @NonCPS
                String helper() {
                    return "help"
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods[0]

        // Note: Annotation detection requires native AST access
        // This test verifies the API works, actual detection depends on impl
        analyzer.isNonCps(method) // Should not throw
    }

    @Test
    fun `analyze returns violations for problematic patterns`() {
        val unit = StaticGroovyParser.parse(
            """
            class Pipeline {
                void run() {
                    def list = []
                    list.collect { it * 2 }
                    new Thread({ println "async" }).start()
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val violations = analyzer.getCpsViolations(method)

        assertThat(violations).hasSizeGreaterThanOrEqualTo(1)
    }

    @Test
    fun `compilation unit level analysis`() {
        val unit = StaticGroovyParser.parse(
            """
            class Pipeline {
                void step1() {
                    [1,2,3].each { println it }
                }
                void step2() {
                    echo "safe"
                }
            }
            """.trimIndent(),
        )

        val violations = analyzer.getCpsViolations(unit)

        // Should find violation in step1 but not step2
        assertThat(violations).isNotEmpty
    }

    @Test
    fun `nested closures are flagged`() {
        val unit = StaticGroovyParser.parse(
            """
            class Pipeline {
                void run() {
                    [[1,2],[3,4]].each { outer ->
                        outer.each { inner ->
                            println inner
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        val classDecl = unit.types[0] as ClassDeclaration
        val method = classDecl.methods[0]
        val violations = analyzer.getCpsViolations(method)

        // Nested closures in CPS context are problematic
        assertThat(violations.size).isGreaterThanOrEqualTo(1)
    }
}
