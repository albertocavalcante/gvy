package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpockBlockCompletionTest {

    @TempDir
    lateinit var workspace: Path

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

    /** Labels at the caret in [file], compiled with the other [sources] of one workspace model. */
    private suspend fun labelsInWorkspace(
        sources: Map<String, String>,
        file: String,
        codeWithCaret: String,
    ): Set<String> {
        val compilationService = GroovyCompilationService(SpockBlockCompletionTest::class.java.classLoader)
        val (content, line, character) = extractCaret(codeWithCaret)
        sources.forEach { (name, text) -> Files.writeString(workspace.resolve(name), text) }
        val target = workspace.resolve(file).also { Files.writeString(it, content) }
        compilationService.updateWorkspaceModel(workspace, emptyList(), listOf(workspace))
        assertTrue(compilationService.compile(target.toUri(), content).isSuccess, "Compilation should succeed")
        return CompletionProvider.getContextualCompletions(
            target.toUri().toString(),
            line,
            character,
            compilationService,
            mockk(relaxed = true),
            content,
        ).map { it.label }.toSet()
    }

    /** End-to-end labels at the caret through [CompletionProvider] and a real compile (#974). */
    private suspend fun labelsAt(
        codeWithCaret: String,
        path: String = "file:///src/test/groovy/Example.groovy",
    ): Set<String> {
        val compilationService = GroovyCompilationService(SpockBlockCompletionTest::class.java.classLoader)
        val (content, line, character) = extractCaret(codeWithCaret)
        val uri = URI.create(path)
        assertTrue(compilationService.compile(uri, content).isSuccess, "Compilation should succeed")
        return CompletionProvider.getContextualCompletions(
            uri.toString(),
            line,
            character,
            compilationService,
            mockk(relaxed = true),
            content,
        ).map { it.label }.toSet()
    }

    @Test
    fun `a helper class next to a spec gets no block labels`(): Unit = runTest {
        val labels = labelsAt(
            """
            import spock.lang.Specification

            class FooSpec extends Specification {
                def "feature"() {
                    expect: true
                }
            }

            class Helper {
                def run() {
                    /*caret*/
                }
            }
            """.trimIndent(),
        )

        assertFalse(labels.contains("given:"), "Helper is a confident non-spec: $labels")
    }

    @Test
    fun `an anonymous class body inside a feature belongs to its spec`(): Unit = runTest {
        val labels = labelsAt(
            """
            import spock.lang.Specification

            class FooSpec extends Specification {
                def "feature"() {
                    def runnable = new Runnable() {
                        void run() {
                            /*caret*/
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        assertTrue(labels.contains("given:"), "$labels")
    }

    @Test
    fun `a nested static spec offers block labels`(): Unit = runTest {
        val labels = labelsAt(
            """
            import spock.lang.Specification

            class Outer {
                static class FooSpec extends Specification {
                    def "feature"() {
                        /*caret*/
                    }
                }
            }
            """.trimIndent(),
        )

        assertTrue(labels.contains("given:"), "$labels")
    }

    @Test
    fun `suggests spock block labels at line start inside spec`(): Unit = runTest {
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
            mockk(relaxed = true),
            content,
        )

        assertTrue(completions.any { it.label == "given:" })
        assertTrue(completions.any { it.label == "when:" })
        assertTrue(completions.any { it.label == "then:" })
        assertTrue(completions.any { it.label == "where:" })
    }

    @Test
    fun `does not suggest spock block labels in non spock file`(): Unit = runTest {
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
            mockk(relaxed = true),
            content,
        )

        assertFalse(completions.any { it.label == "given:" })
        assertFalse(completions.any { it.label == "where:" })
    }

    @Test
    fun `does not suggest spock block labels when spock import is only in a comment`(): Unit = runTest {
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
            mockk(relaxed = true),
            content,
        )

        assertFalse(completions.any { it.label == "given:" })
        assertFalse(completions.any { it.label == "where:" })
    }

    @Test
    fun `does not suggest spock block labels when file name ends with Spec groovy but class is not a spec`(): Unit =
        runTest {
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
                mockk(relaxed = true),
                content,
            )

            assertFalse(completions.any { it.label == "given:" })
            assertFalse(completions.any { it.label == "where:" })
        }

    @Test
    fun `does not suggest spock block labels when cursor is mid line`(): Unit = runTest {
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
            mockk(relaxed = true),
            content,
        )

        assertFalse(completions.any { it.label == "given:" })
        assertFalse(completions.any { it.label == "where:" })
    }

    @Test
    fun `does not suggest spock block labels inside multiline comment`(): Unit = runTest {
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
            mockk(relaxed = true),
            content,
        )

        assertFalse(completions.any { it.label == "given:" })
        assertFalse(completions.any { it.label == "where:" })
    }

    @Test
    fun `does not suggest spock block labels inside multiline string`(): Unit = runTest {
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
            mockk(relaxed = true),
            content,
        )

        assertFalse(completions.any { it.label == "given:" })
        assertFalse(completions.any { it.label == "where:" })
    }

    @Test
    fun `a class extending an ordinary base from another file gets no labels despite a spock import`() = runTest {
        val labels = labelsInWorkspace(
            mapOf("Base.groovy" to "class Base {}\n"),
            "Helper.groovy",
            """
            import spock.lang.Specification

            class Helper extends Base {
                def check() {
                    /*caret*/
                }
            }
            """.trimIndent(),
        )

        assertFalse(labels.contains("given:"), "Expected no Spock labels, got: $labels")
    }

    @Test
    fun `a spec extending a base spec from another file gets labels without spock text markers`() = runTest {
        val labels = labelsInWorkspace(
            mapOf("BaseSpec.groovy" to "import spock.lang.Specification\nclass BaseSpec extends Specification {}\n"),
            "FooTest.groovy",
            """
            class FooTest extends BaseSpec {
                def "feature"() {
                    /*caret*/
                }
            }
            """.trimIndent(),
        )

        assertTrue(labels.contains("given:"), "Expected Spock labels, got: $labels")
    }
}
