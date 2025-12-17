package com.github.albertocavalcante.groovyjenkins

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TDD tests for vars/ documentation loading from .txt files.
 */
class VarsDocumentationTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        tempDir = Files.createTempDirectory("vars-doc-test")
    }

    @AfterEach
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `should read documentation from companion txt file`() {
        // Given: A vars directory with both .groovy and .txt files
        val varsDir = Files.createDirectory(tempDir.resolve("vars"))

        Files.writeString(
            varsDir.resolve("buildPlugin.groovy"),
            "def call() { println 'Building plugin' }",
        )
        Files.writeString(
            varsDir.resolve("buildPlugin.txt"),
            """
            <p>Builds a Jenkins plugin using Maven.</p>
            <p>See <b>README</b> for usage.</p>
            """.trimIndent(),
        )

        // When: Loading global variables
        val provider = VarsGlobalVariableProvider(tempDir)
        val variables = provider.getGlobalVariables()

        // Then: Documentation should be loaded and converted to markdown
        assertEquals(1, variables.size)
        val buildPlugin = variables.first()
        assertEquals("buildPlugin", buildPlugin.name)
        assertTrue(buildPlugin.documentation.isNotEmpty(), "Documentation should be loaded")
        assertTrue(
            buildPlugin.documentation.contains("Builds a Jenkins plugin"),
            "Documentation content should be preserved",
        )
    }

    @Test
    fun `should handle missing txt file gracefully`() {
        // Given: A vars directory with only .groovy file (no .txt)
        val varsDir = Files.createDirectory(tempDir.resolve("vars"))

        Files.writeString(
            varsDir.resolve("buildPlugin.groovy"),
            "def call() { println 'Building plugin' }",
        )

        // When: Loading global variables
        val provider = VarsGlobalVariableProvider(tempDir)
        val variables = provider.getGlobalVariables()

        // Then: Variable should be loaded with empty documentation
        assertEquals(1, variables.size)
        assertEquals("buildPlugin", variables.first().name)
        assertEquals("", variables.first().documentation)
    }

    @Test
    fun `should parse HTML to markdown correctly`() {
        // Given: Vars with HTML documentation
        val varsDir = Files.createDirectory(tempDir.resolve("vars"))

        Files.writeString(
            varsDir.resolve("myStep.groovy"),
            "def call(Map params) { }",
        )
        Files.writeString(
            varsDir.resolve("myStep.txt"),
            """
            <p>
                This is the <b>main</b> description.
            </p>
            <p>
                Use <code>myStep()</code> to invoke.
            </p>
            """.trimIndent(),
        )

        // When: Loading global variables
        val provider = VarsGlobalVariableProvider(tempDir)
        val variables = provider.getGlobalVariables()

        // Then: HTML should be converted to markdown
        val doc = variables.first().documentation
        assertTrue(doc.contains("main") || doc.contains("**main**"), "Bold should be converted")
        assertTrue(doc.contains("myStep()") || doc.contains("`myStep()`"), "Code should be preserved")
    }

    @Test
    fun `should strip HTML comments from documentation`() {
        // Given: Vars with HTML comments
        val varsDir = Files.createDirectory(tempDir.resolve("vars"))

        Files.writeString(
            varsDir.resolve("commented.groovy"),
            "def call() { }",
        )
        Files.writeString(
            varsDir.resolve("commented.txt"),
            """
            <p>Real documentation</p>
            <!-- vim: ft=html -->
            """.trimIndent(),
        )

        // When: Loading global variables
        val provider = VarsGlobalVariableProvider(tempDir)
        val variables = provider.getGlobalVariables()

        // Then: Comments should be stripped
        val doc = variables.first().documentation
        assertTrue(doc.contains("Real documentation"), "Real content should remain")
        assertTrue(!doc.contains("vim:"), "HTML comments should be stripped")
    }

    @Test
    fun `should handle multiple vars with mixed documentation`() {
        // Given: Multiple vars, some with .txt, some without
        val varsDir = Files.createDirectory(tempDir.resolve("vars"))

        Files.writeString(varsDir.resolve("step1.groovy"), "def call() { }")
        Files.writeString(varsDir.resolve("step1.txt"), "<p>Step 1 docs</p>")

        Files.writeString(varsDir.resolve("step2.groovy"), "def call() { }")
        // step2 has no .txt file

        Files.writeString(varsDir.resolve("step3.groovy"), "def call() { }")
        Files.writeString(varsDir.resolve("step3.txt"), "<p>Step 3 docs</p>")

        // When: Loading global variables
        val provider = VarsGlobalVariableProvider(tempDir)
        val variables = provider.getGlobalVariables()

        // Then: All should be loaded, with correct documentation status
        assertEquals(3, variables.size)
        val byName = variables.associateBy { it.name }

        assertTrue(byName["step1"]!!.documentation.isNotEmpty())
        assertTrue(byName["step2"]!!.documentation.isEmpty())
        assertTrue(byName["step3"]!!.documentation.isNotEmpty())
    }
}
