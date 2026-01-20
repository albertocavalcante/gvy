package com.github.albertocavalcante.gvy.gls.providers.completion.strategy

import com.github.albertocavalcante.groovyparser.resolution.typesolvers.ReflectionTypeSolver
import com.github.albertocavalcante.gvy.gls.test.LspTestFixture
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionItem
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Test suite for ImportCompletionStrategy.
 *
 * Tests import completion features including:
 * - Static keyword suggestions
 * - Class name completions by prefix
 * - Static method completions for fully qualified classes
 * - Static field and constant completions
 * - Member filtering by prefix
 * - Workspace class static member completions
 * - Empty result handling for non-existent classes
 */
class ImportCompletionStrategyTest {

    private lateinit var fixture: LspTestFixture

    @BeforeEach
    fun setUp() {
        fixture = LspTestFixture()
    }

    private fun completionsAt(line: Int, character: Int): List<CompletionItem> = runBlocking {
        val content = fixture.documentProvider.get(fixture.uri) ?: ""
        com.github.albertocavalcante.gvy.gls.providers.completion.CompletionProvider.getContextualCompletions(
            fixture.uri.toString(),
            line,
            character,
            fixture.compilationService,
            SemanticTypeResolver(ReflectionTypeSolver()),
            content,
        )
    }

    @Test
    fun `suggests static keyword when not yet present`() {
        val code = """
            import java.util.List

            class Sample {}
        """.trimIndent()

        fixture.compile(code)

        fixture.assertCompletionContains(0, 7, "static")
    }

    @Test
    fun `completes class names by prefix`() {
        val code = """
            import java.util.L

            class Sample {}
        """.trimIndent()

        fixture.documentProvider.put(fixture.uri, code)

        val items = completionsAt(0, 18)
        assertTrue(items.any { it.label == "java.util.List" })
        assertTrue(items.any { it.label == "java.util.LinkedList" })
    }

    @Test
    fun `completes static methods for fully qualified class`() {
        val line = 0
        val lineContent = "import static java.lang.Math."
        val code = """
            $lineContent

            class Sample {}
        """.trimIndent()

        fixture.documentProvider.put(fixture.uri, code)

        val items = completionsAt(line, lineContent.length)
        val maxItem = items.find { it.label == "max" }
        assertNotNull(maxItem, "Should suggest Math.max static method")

        val edit = maxItem.textEdit?.left
        assertNotNull(edit)
        assertTrue(edit.newText == "java.lang.Math.max")
    }

    @Test
    fun `completes static fields and constants`() {
        val line = 0
        val lineContent = "import static java.lang.Math."
        val code = """
            $lineContent

            class Sample {}
        """.trimIndent()

        fixture.documentProvider.put(fixture.uri, code)

        val items = completionsAt(line, lineContent.length)
        val piItem = items.find { it.label == "PI" }
        assertNotNull(piItem, "Should suggest Math.PI constant")

        val edit = piItem.textEdit?.left
        assertNotNull(edit)
        assertTrue(edit.newText == "java.lang.Math.PI")
    }

    @Test
    fun `filters by member prefix after class name`() {
        val line = 0
        val lineContent = "import static java.lang.Math.P"
        val code = """
            $lineContent

            class Sample {}
        """.trimIndent()

        fixture.documentProvider.put(fixture.uri, code)

        val items = completionsAt(line, lineContent.length)
        val piItem = items.find { it.label == "PI" }
        assertNotNull(piItem, "Should suggest PI when prefix matches")

        // max should not be in the list since it doesn't start with 'P'
        val maxItem = items.find { it.label == "max" }
        assertTrue(maxItem == null, "Should not suggest max when prefix is 'P'")
    }

    @Test
    fun `handles workspace classes with static members`() = runBlocking {
        // First, compile the workspace class in a separate file
        val workspaceClassUri = URI.create("file:///MyUtils.groovy")
        val workspaceCode = """
            class MyUtils {
                static final String VERSION = "1.0"
                static int add(int a, int b) { return a + b }
            }
        """.trimIndent()

        fixture.documentProvider.put(workspaceClassUri, workspaceCode)
        fixture.compilationService.compile(workspaceClassUri, workspaceCode)

        // Now test completions in the main file with incomplete import
        val code = """
            import static MyUtils.

            class Sample {}
        """.trimIndent()

        fixture.documentProvider.put(fixture.uri, code)

        val items = completionsAt(0, 27)
        val versionItem = items.find { it.label == "VERSION" }
        val addItem = items.find { it.label == "add" }

        assertNotNull(versionItem, "Should suggest static constant VERSION from workspace class")
        assertNotNull(addItem, "Should suggest static method add from workspace class")
    }

    @Test
    fun `returns empty when class not found`() {
        val line = 0
        val lineContent = "import static com.nonexistent.Class."
        val code = """
            $lineContent

            class Sample {}
        """.trimIndent()

        fixture.documentProvider.put(fixture.uri, code)

        val items = completionsAt(line, lineContent.length)
        // Should return empty or just the class name prefix, but no members
        val hasMemberCompletions = items.any { it.label.contains(".") && !it.label.startsWith("com.") }
        assertTrue(!hasMemberCompletions, "Should not suggest members for non-existent class")
    }

    @Test
    fun `does not suggest static keyword in static import context`() {
        val code = """
            import static java.lang.Math.*

            class Sample {}
        """.trimIndent()

        fixture.compile(code)

        fixture.assertCompletionDoesNotContain(0, 28, "static")
    }

    @Test
    fun `replaces full qualified name when completing`() {
        val code = """
            import java.util.List

            class Sample {}
        """.trimIndent()

        fixture.compile(code)

        val lineText = "import java.util.List"
        val items = completionsAt(0, lineText.length)
        val listItem = items.find { it.label == "java.util.List" }
        assertNotNull(listItem)

        val edit = listItem.textEdit?.left
        assertNotNull(edit)
        assertTrue(edit.range.start.line == 0 && edit.range.start.character == 7)
        assertTrue(edit.range.end.line == 0 && edit.range.end.character == lineText.length)
        assertTrue(edit.newText == "java.util.List")
    }

    @Test
    fun `supports simple class name prefix`() {
        val code = """
            import Arr

            class Sample {}
        """.trimIndent()

        fixture.documentProvider.put(fixture.uri, code)

        val items = completionsAt(0, 10)
        // Should find classes like Array, ArrayDeque, etc.
        assertTrue(
            items.any {
                it.label.contains("java.util.ArrayDeque")
            },
            "Expected to find ArrayDeque in completions",
        )
        assertTrue(items.any { it.label.contains("java.lang.reflect.Array") }, "Expected to find Array in completions")
    }

    @Test
    fun `handles workspace static properties`() = runBlocking {
        // First, compile the workspace class in a separate file
        val workspaceClassUri = URI.create("file:///Config.groovy")
        val workspaceCode = """
            class Config {
                static String appName = "MyApp"
                static getVersion() { "1.0" }
            }
        """.trimIndent()

        fixture.documentProvider.put(workspaceClassUri, workspaceCode)
        fixture.compilationService.compile(workspaceClassUri, workspaceCode)

        // Now test completions in the main file with incomplete import
        val code = """
            import static Config.

            class Sample {}
        """.trimIndent()

        fixture.documentProvider.put(fixture.uri, code)

        val items = completionsAt(0, 25)
        val appNameItem = items.find { it.label == "appName" }
        assertNotNull(appNameItem, "Should suggest static property appName from workspace class")
    }
}
