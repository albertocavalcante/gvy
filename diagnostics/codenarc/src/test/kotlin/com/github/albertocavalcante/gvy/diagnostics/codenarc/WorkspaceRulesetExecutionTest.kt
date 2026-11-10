package com.github.albertocavalcante.gvy.diagnostics.codenarc

import com.github.albertocavalcante.gvy.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.gvy.diagnostics.api.WorkspaceContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertFalse

/**
 * CodeNarc evaluates a Groovy-DSL ruleset file as a Groovy script, so a ruleset shipped inside the workspace
 * must never be able to run arbitrary code just because a file was analysed. The scripts below only write a
 * marker file into a temp directory.
 */
class WorkspaceRulesetExecutionTest {
    @TempDir
    lateinit var tempDir: Path

    private fun context(root: Path) = object : WorkspaceContext {
        override val root: Path = root
        override fun getConfiguration() = object : DiagnosticConfiguration {
            override val isEnabled = true
            override val propertiesFile: String? = null
            override val autoDetectConfig = true
        }
    }

    private fun analyzeWith(workspaceFile: String, ruleset: String) {
        val workspace = tempDir.resolve("ws").also { Files.createDirectories(it) }
        val file = workspace.resolve(workspaceFile)
        Files.createDirectories(file.parent)
        Files.writeString(file, ruleset)

        val resolved = HierarchicalRulesetResolver().resolve(context(workspace))
        runCatching {
            DefaultCodeAnalyzer().analyze("class A {}\n", "A.groovy", resolved.rulesetContent, resolved.propertiesFile)
        }
    }

    private fun marker(name: String): Pair<Path, String> {
        val marker = tempDir.resolve(name)
        return marker to marker.toString().replace("\\", "/")
    }

    @Test
    fun `a workspace dot-codenarc ruleset cannot run code`() {
        val (marker, path) = marker("dot-codenarc")

        analyzeWith(".codenarc", "ruleset { new File('$path').text = 'x'\n TrailingWhitespace }")

        assertFalse(marker.exists(), "the workspace ruleset executed code")
    }

    @Test
    fun `a workspace codenarc dot groovy ruleset cannot run code`() {
        val (marker, path) = marker("codenarc-groovy")

        analyzeWith("codenarc.groovy", "new File('$path').text = 'x'\nruleset { TrailingWhitespace }")

        assertFalse(marker.exists(), "the workspace ruleset executed code")
    }

    @Test
    fun `a nested config ruleset cannot run code from inside the DSL closure either`() {
        val (marker, path) = marker("config-rules")

        analyzeWith(
            "config/codenarc/rules.groovy",
            "ruleset {\n description \"\${new File('$path').text = 'x'}\"\n TrailingWhitespace { priority = 2 }\n}",
        )

        assertFalse(marker.exists(), "the workspace ruleset executed code")
    }

    @Test
    fun `evaluate through resolveStrategy cannot run code`() {
        val (marker, path) = marker("evaluate")

        analyzeWith(
            ".codenarc",
            "ruleset { resolveStrategy = 2\n evaluate 'new File(\"$path\").text = \"x\"' }",
        )

        assertFalse(marker.exists(), "evaluate reached Script.evaluate")
    }

    @Test
    fun `nested evaluate cannot run code`() {
        val (marker, path) = marker("nested-evaluate")

        analyzeWith(
            "codenarc.groovy",
            "ruleset { TrailingWhitespace { resolveStrategy = 2\n evaluate 'new File(\"$path\").text = \"x\"' } }",
        )

        assertFalse(marker.exists(), "nested evaluate executed code")
    }

    @Test
    fun `ruleset and rule references to groovy files cannot run code`() {
        val (marker, path) = marker("file-ref")
        val evil = tempDir.resolve("evil.groovy")
        Files.writeString(evil, "new File('$path').text = 'x'\nruleset { TrailingWhitespace }")
        val uri = evil.toUri().toString()

        analyzeWith(".codenarc", "ruleset { ruleset('$uri') }")
        assertFalse(marker.exists(), "ruleset('file:...groovy') loaded Groovy")

        analyzeWith("config/codenarc/rules.groovy", "ruleset { rule('$uri') }")
        assertFalse(marker.exists(), "rule('file:...groovy') loaded Groovy")

        analyzeWith("codenarc.groovy", "ruleset { ruleset('${evil.toString().replace("\\", "/")}') }")
        assertFalse(marker.exists(), "ruleset with an absolute path loaded Groovy")
    }

    @Test
    fun `an XML ruleset with a rule-script cannot run code`() {
        val (marker, path) = marker("rule-script")
        val script = tempDir.resolve("rule.groovy")
        Files.writeString(script, "new File('$path').text = 'x'")
        val xml = """
            <ruleset xmlns="http://codenarc.org/ruleset/1.0">
              <rule-script path="${script.toUri()}"/>
            </ruleset>
        """.trimIndent()

        analyzeWith(".codenarc", xml)

        assertFalse(marker.exists(), "rule-script executed code")
    }

    @Test
    fun `an ignored workspace ruleset is reported to the resolver's caller`() {
        val workspace = tempDir.resolve("ws2").also { Files.createDirectories(it) }
        Files.writeString(workspace.resolve(".codenarc"), "ruleset { evaluate 'println 1' }")

        val resolved = HierarchicalRulesetResolver().resolve(context(workspace))

        kotlin.test.assertEquals(listOf(".codenarc"), resolved.ignoredWorkspaceRulesets)
    }
}
