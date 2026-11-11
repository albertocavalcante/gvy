package com.github.albertocavalcante.gvy.diagnostics.codenarc

import com.github.albertocavalcante.gvy.diagnostics.api.DiagnosticConfiguration
import com.github.albertocavalcante.gvy.diagnostics.api.WorkspaceContext
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.codenarc.results.Results
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CodeNarcFailureBoundaryTest {
    @TempDir
    lateinit var analysisRoot: Path

    @Test
    fun `resolver IO failure cannot become healthy empty diagnostics`() {
        val failure = IOException("ruleset unreadable")
        val resolver = object : RulesetResolver {
            override fun resolve(context: WorkspaceContext): RulesetConfiguration = throw failure
        }
        val analyzer = mockk<CodeAnalyzer>()
        val provider = CodeNarcDiagnosticProvider(workspace(), rulesetResolver = resolver, codeAnalyzer = analyzer)

        val observed = assertFailsWith<IOException> {
            provider.analyzeAndGetDiagnostics(SOURCE, FILE_NAME)
        }

        assertSame(failure, observed)
        verify(exactly = 0) { analyzer.analyze(any(), any(), any(), any()) }
    }

    @Test
    fun `missing packaged rulesets cannot become healthy empty diagnostics`() {
        val failure = IllegalStateException("Failed to load any ruleset")
        val resolver = object : RulesetResolver {
            override fun resolve(context: WorkspaceContext): RulesetConfiguration = throw failure
        }
        val provider = CodeNarcDiagnosticProvider(workspace(), rulesetResolver = resolver)

        assertSame(
            failure,
            assertFailsWith<IllegalStateException> { provider.analyzeAndGetDiagnostics(SOURCE, FILE_NAME) },
        )
    }

    @Test
    fun `analysis IO failure reaches the provider boundary`() {
        val failure = IOException("analysis unavailable")
        val provider = provider(failingAnalyzer(failure))

        assertSame(failure, assertFailsWith<IOException> { provider.analyzeAndGetDiagnostics(SOURCE, FILE_NAME) })
    }

    @Test
    fun `analysis cancellation cannot become healthy empty diagnostics`() {
        val failure = CancellationException("analysis cancelled")
        val provider = provider(failingAnalyzer(failure))

        assertSame(
            failure,
            assertFailsWith<CancellationException> { provider.analyzeAndGetDiagnostics(SOURCE, FILE_NAME) },
        )
    }

    @Test
    fun `fatal analysis failure propagates`() {
        val failure = LinkageError("analysis linkage failed")
        val provider = provider(failingAnalyzer(failure))

        assertSame(failure, assertFailsWith<LinkageError> { provider.analyzeAndGetDiagnostics(SOURCE, FILE_NAME) })
    }

    @Test
    fun `disabled CodeNarc does not resolve or analyze`(): Unit = runBlocking {
        val resolver = mockk<RulesetResolver>()
        val analyzer = mockk<CodeAnalyzer>()
        val provider = CodeNarcDiagnosticProvider(
            workspace(enabled = false),
            rulesetResolver = resolver,
            codeAnalyzer = analyzer,
        )

        assertEquals(emptyList(), provider.analyze(SOURCE, URI.create("file:///workspace/$FILE_NAME")))
        verify(exactly = 0) { resolver.resolve(any(), any()) }
        verify(exactly = 0) { analyzer.analyze(any(), any(), any(), any()) }
    }

    @Test
    fun `a real unknown rule with healthy source fails instead of returning empty diagnostics`() {
        val provider = CodeNarcDiagnosticProvider(
            workspace(),
            rulesetResolver = ruleset("ruleset { DefinitelyMissingRuleForBoundaryTest }"),
            codeAnalyzer = DefaultCodeAnalyzer(),
        )

        val failure = assertFailsWith<AssertionError> { provider.analyzeAndGetDiagnostics(SOURCE, FILE_NAME) }
        assertTrue(checkNotNull(failure.message).contains("DefinitelyMissingRuleForBoundaryTest"))
    }

    @Test
    fun `a real malformed XML ruleset with healthy source cannot return empty diagnostics`() {
        val provider = CodeNarcDiagnosticProvider(
            workspace(),
            rulesetResolver = ruleset("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ruleset>"),
            codeAnalyzer = DefaultCodeAnalyzer(),
        )

        assertFailsWith<CodeAnalysisException> { provider.analyzeAndGetDiagnostics(SOURCE, FILE_NAME) }
    }

    @Test
    fun `a valid real ruleset can analyze syntax invalid source without becoming a ruleset failure`() {
        val result = DefaultCodeAnalyzer().analyze(
            "class Example { def broken = }",
            FILE_NAME,
            "ruleset { UnusedVariable }",
            propertiesFile = null,
        )

        assertEquals(0, result.getNumberOfViolationsWithPriority(HIGH_PRIORITY, true))
    }

    @Test
    fun `cache cancellation does not fall back to writing a ruleset`() {
        val failure = CancellationException("cache cancelled")
        val analyzer = DefaultCodeAnalyzer(rulesetFilePathProvider = { throw failure })

        assertSame(
            failure,
            assertFailsWith<CancellationException> { analyzer.resolveRulesetFile(analysisRoot, VALID_RULESET) },
        )
        assertFalse(Files.exists(analysisRoot.resolve("ruleset.groovy")))
    }

    @Test
    fun `real analysis preserves ruleset cancellation identity`() {
        val failure = CancellationException("analysis ruleset cancelled")
        val analyzer = DefaultCodeAnalyzer(rulesetFilePathProvider = { throw failure })

        assertSame(
            failure,
            assertFailsWith<CancellationException> {
                analyzer.analyze(SOURCE, FILE_NAME, VALID_RULESET, propertiesFile = null)
            },
        )
    }

    private fun provider(analyzer: CodeAnalyzer) = CodeNarcDiagnosticProvider(
        workspace(),
        rulesetResolver = ruleset(),
        codeAnalyzer = analyzer,
    )

    private fun failingAnalyzer(failure: Throwable): CodeAnalyzer = object : CodeAnalyzer {
        override fun analyze(
            sourceCode: String,
            fileName: String,
            rulesetContent: String,
            propertiesFile: String?,
        ): Results = throw failure
    }

    private fun ruleset(content: String = VALID_RULESET): RulesetResolver = object : RulesetResolver {
        override fun resolve(context: WorkspaceContext) = RulesetConfiguration(content, source = "test")
    }

    private fun workspace(enabled: Boolean = true): WorkspaceContext = object : WorkspaceContext {
        override val root: Path? = null
        override fun getConfiguration(): DiagnosticConfiguration = object : DiagnosticConfiguration {
            override val isEnabled: Boolean = enabled
            override val propertiesFile: String? = null
            override val autoDetectConfig: Boolean = false
        }
    }

    private companion object {
        const val SOURCE = "class Example {}"
        const val FILE_NAME = "Example.groovy"
        const val VALID_RULESET = "ruleset { TrailingWhitespace }"
        const val HIGH_PRIORITY = 1
    }
}
