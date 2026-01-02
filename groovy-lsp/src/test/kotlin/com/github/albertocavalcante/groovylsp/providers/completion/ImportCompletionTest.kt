package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovylsp.test.LspTestFixture
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ImportCompletionTest {

    private lateinit var fixture: LspTestFixture

    @BeforeEach
    fun setUp() {
        fixture = LspTestFixture()
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
}
