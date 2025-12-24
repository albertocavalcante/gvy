package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpockBlockCompletionTest {

    private fun extractCaret(codeWithCaret: String): Triple<String, Int, Int> {
        val marker = "/*caret*/"
        val markerIndex = codeWithCaret.indexOf(marker)
        require(markerIndex >= 0) { "Missing caret marker: $marker" }

        val before = codeWithCaret.substring(0, markerIndex)
        val line = before.count { it == '\n' }
        val col = before.substringAfterLast('\n').length

        val clean = codeWithCaret.removeRange(markerIndex, markerIndex + marker.length)
        return Triple(clean, line, col)
    }

    @Test
    fun `suggests spock block labels at line start inside spec`() = runTest {
        val compilationService = GroovyCompilationService(SpockBlockCompletionTest::class.java.classLoader)

        val (content, line, character) =
            extractCaret(
                """
                import spock.lang.Specification

                class FooSpec extends Specification {
                    def "feature"() {
                        /*caret*/
                    }
                }
                """.trimIndent(),
            )

        val uri = URI.create("file:///src/test/groovy/com/example/FooSpec.groovy")
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        val completions = CompletionProvider.getContextualCompletions(
            uri.toString(),
            line,
            character,
            compilationService,
            content,
        )

        assertTrue(completions.any { it.label == "given:" })
        assertTrue(completions.any { it.label == "when:" })
        assertTrue(completions.any { it.label == "then:" })
        assertTrue(completions.any { it.label == "where:" })
    }

    @Test
    fun `does not suggest spock block labels in non spock file`() = runTest {
        val compilationService = GroovyCompilationService(SpockBlockCompletionTest::class.java.classLoader)

        val (content, line, character) =
            extractCaret(
                """
                class Foo {
                    def method() {
                        /*caret*/
                    }
                }
                """.trimIndent(),
            )

        val uri = URI.create("file:///src/main/groovy/com/example/Foo.groovy")
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        val completions = CompletionProvider.getContextualCompletions(
            uri.toString(),
            line,
            character,
            compilationService,
            content,
        )

        assertFalse(completions.any { it.label == "given:" })
        assertFalse(completions.any { it.label == "where:" })
    }

    @Test
    fun `does not suggest spock block labels when spock import is only in a comment`() = runTest {
        val compilationService = GroovyCompilationService(SpockBlockCompletionTest::class.java.classLoader)

        val (content, line, character) =
            extractCaret(
                """
                /*
                import spock.lang.Specification
                */
                class Foo {
                    def method() {
                        /*caret*/
                    }
                }
                """.trimIndent(),
            )

        val uri = URI.create("file:///src/main/groovy/com/example/Foo.groovy")
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        val completions = CompletionProvider.getContextualCompletions(
            uri.toString(),
            line,
            character,
            compilationService,
            content,
        )

        assertFalse(completions.any { it.label == "given:" })
        assertFalse(completions.any { it.label == "where:" })
    }

    @Test
    fun `does not suggest spock block labels when file name ends with Spec groovy but class is not a spec`() = runTest {
        val compilationService = GroovyCompilationService(SpockBlockCompletionTest::class.java.classLoader)

        val (content, line, character) =
            extractCaret(
                """
                class FooSpec {
                    def feature() {
                        /*caret*/
                    }
                }
                """.trimIndent(),
            )

        val uri = URI.create("file:///src/test/groovy/com/example/FooSpec.groovy")
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        val completions = CompletionProvider.getContextualCompletions(
            uri.toString(),
            line,
            character,
            compilationService,
            content,
        )

        assertFalse(completions.any { it.label == "given:" })
        assertFalse(completions.any { it.label == "where:" })
    }

    @Test
    fun `does not suggest spock block labels when cursor is mid line`() = runTest {
        val compilationService = GroovyCompilationService(SpockBlockCompletionTest::class.java.classLoader)

        val (content, line, character) =
            extractCaret(
                """
                import spock.lang.Specification

                class FooSpec extends Specification {
                    def "feature"() {
                        def value = 1/*caret*/
                    }
                }
                """.trimIndent(),
            )

        val uri = URI.create("file:///src/test/groovy/com/example/FooSpec.groovy")
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        val completions = CompletionProvider.getContextualCompletions(
            uri.toString(),
            line,
            character,
            compilationService,
            content,
        )

        assertFalse(completions.any { it.label == "given:" })
        assertFalse(completions.any { it.label == "where:" })
    }

    @Test
    fun `does not suggest spock block labels inside multiline comment`() = runTest {
        val compilationService = GroovyCompilationService(SpockBlockCompletionTest::class.java.classLoader)

        val (content, line, character) =
            extractCaret(
                """
                import spock.lang.Specification

                class FooSpec extends Specification {
                    def "feature"() {
                        /*
                            /*caret*/
                        */
                    }
                }
                """.trimIndent(),
            )

        val uri = URI.create("file:///src/test/groovy/com/example/FooSpec.groovy")
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        val completions = CompletionProvider.getContextualCompletions(
            uri.toString(),
            line,
            character,
            compilationService,
            content,
        )

        assertFalse(completions.any { it.label == "given:" })
        assertFalse(completions.any { it.label == "where:" })
    }

    @Test
    fun `does not suggest spock block labels inside multiline string`() = runTest {
        val compilationService = GroovyCompilationService(SpockBlockCompletionTest::class.java.classLoader)

        val (content, line, character) =
            extractCaret(
                """
                import spock.lang.Specification

                class FooSpec extends Specification {
                    def "feature"() {
                        def text = '''
                            /*caret*/
                        '''
                    }
                }
                """.trimIndent(),
            )

        val uri = URI.create("file:///src/test/groovy/com/example/FooSpec.groovy")
        val result = compilationService.compile(uri, content)
        assertTrue(result.isSuccess, "Compilation should succeed")

        val completions = CompletionProvider.getContextualCompletions(
            uri.toString(),
            line,
            character,
            compilationService,
            content,
        )

        assertFalse(completions.any { it.label == "given:" })
        assertFalse(completions.any { it.label == "where:" })
    }
}
