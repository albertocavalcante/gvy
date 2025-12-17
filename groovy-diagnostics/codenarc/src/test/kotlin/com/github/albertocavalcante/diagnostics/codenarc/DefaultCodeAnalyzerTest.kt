package com.github.albertocavalcante.diagnostics.codenarc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultCodeAnalyzerTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `safe file name uses the final path segment`() {
        val analyzer = DefaultCodeAnalyzer()

        val safe = analyzer.resolveSafeFileName("some/dir/Example.groovy")

        assertEquals("Example.groovy", safe)
    }

    @Test
    fun `safe file name falls back when the path is invalid`() {
        val analyzer = DefaultCodeAnalyzer()

        val safe = analyzer.resolveSafeFileName("\u0000")

        assertEquals("script.groovy", safe)
    }

    @Test
    fun `ruleset file fallback writes a per analysis ruleset file`() {
        val analyzer = DefaultCodeAnalyzer(rulesetFilePathProvider = { error("boom") })
        val rulesetContent = "ruleset { TrailingWhitespace }"

        val rulesetFile = analyzer.resolveRulesetFile(dir, rulesetContent)

        assertEquals(dir.resolve("ruleset.groovy"), rulesetFile)
        assertTrue(Files.exists(rulesetFile))
        assertEquals(rulesetContent, Files.readString(rulesetFile))
    }

    @Test
    fun `ruleset file uses the provider path when available`() {
        val cachedRuleset = dir.resolve("cached.groovy").also {
            Files.writeString(it, "cached")
        }
        val analyzer = DefaultCodeAnalyzer(rulesetFilePathProvider = { cachedRuleset })

        val rulesetFile = analyzer.resolveRulesetFile(dir, "ruleset { TrailingWhitespace }")

        assertEquals(cachedRuleset, rulesetFile)
        assertTrue(Files.exists(rulesetFile))
        assertTrue(!Files.exists(dir.resolve("ruleset.groovy")))
    }
}
