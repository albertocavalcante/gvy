package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovylsp.test.LspTestFixture
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionItem
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImportCompletionTest {

    private lateinit var fixture: LspTestFixture

    @BeforeEach
    fun setUp() {
        fixture = LspTestFixture()
    }

    private fun completionsAt(line: Int, character: Int): List<CompletionItem> = runBlocking {
        val content = fixture.documentProvider.get(fixture.uri) ?: ""
        CompletionProvider.getContextualCompletions(
            fixture.uri.toString(),
            line,
            character,
            fixture.compilationService,
            content,
        )
    }

    @Test
    fun `import completion suggests static keyword and class candidates`() {
        val code = """
            import java.util.List

            class BuildPluginWithGradle {
                def buildDockerAndPublishImage() {}
                def build = 1
            }

            def archiveArtifacts = { }
        """.trimIndent()

        fixture.compile(code)

        fixture.assertCompletionContains(0, 7, "static")
        fixture.assertCompletionDoesNotContain(
            0,
            7,
            "abstract",
            "as",
            "assert",
            "boolean",
            "break",
            "build",
            "buildDockerAndPublishImage",
            "archiveArtifacts",
            // Ensure internal dummy identifiers do not leak into import completions.
            "BrazilWorldCup2026",
            "class",
            "def",
            "println",
        )
    }

    @Test
    fun `import completion respects qualified prefix`() {
        val code = """
            import java.util.List
        """.trimIndent()

        fixture.compile(code)

        fixture.assertCompletionContains(0, 18, "java.util.List")
        fixture.assertCompletionDoesNotContain(0, 18, "class", "println")
    }

    @Test
    fun `import static completion omits static keyword and unrelated symbols`() {
        val code = """
            import static java.lang.Math.*

            class Sample {
                def value = 1
            }
        """.trimIndent()

        fixture.compile(code)

        fixture.assertCompletionContains(0, 28, "java.lang.Math")
        fixture.assertCompletionDoesNotContain(
            0,
            28,
            "static",
            "abstract",
            "assert",
            "class",
            "def",
            "value",
            "println",
        )
    }

    @Test
    fun `import completion replaces qualified prefix`() {
        val code = """
            import java.util.List
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
    fun `import completion ignores block comment with crlf`() {
        val code = "/*\r\nimport java.util.L\r\n*/\r\nclass Sample {}\r\n"

        fixture.compile(code)

        val lineText = "import java.util.L"
        val items = completionsAt(1, lineText.length)
        assertTrue(items.none { it.label == "java.util.List" })
    }
}
