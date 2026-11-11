package com.github.albertocavalcante.gvy.gls.providers.diagnostics.rules

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.DiagnosticRuleConfig
import com.github.albertocavalcante.gvy.gls.providers.diagnostics.StreamingDiagnosticProvider
import com.github.albertocavalcante.gvy.gls.services.DiagnosticsService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CustomRuleOutcomesTest {
    private val uri = URI.create("file:///workspace/Rules.groovy")
    private val source = "class Rules {}"
    private val compilation = mockk<GroovyCompilationService>().also {
        every { it.getDiagnostics(uri) } returns emptyList()
        every { it.getAst(uri) } returns null
    }

    @Test
    fun `ordinary failures retain healthy rule order and healthy provider results`(): Unit = runBlocking {
        val visited = mutableListOf<String>()
        val failure = IOException("rule unavailable")
        val rules = listOf(
            rule("first") {
                visited += "first"
                listOf(diagnostic("first"))
            },
            rule("failed") {
                visited += "failed"
                throw failure
            },
            rule("last") {
                visited += "last"
                listOf(diagnostic("last"))
            },
        )

        val result = service(rules).collectDiagnostics(uri, source)

        assertEquals(listOf("first", "failed", "last"), visited)
        assertEquals(listOf("first", "last"), result.diagnostics.map { it.message }.filter { it != "peer" })
        assertTrue(result.diagnostics.any { it.message == "peer" })
        assertEquals(listOf("custom-rules", "healthy-peer"), result.providerIds)
        assertFalse(result.isSuccessful)
        val recorded = result.failures.single()
        assertEquals("custom-rules", recorded.providerId)
        assertTrue(checkNotNull(recorded.message).contains("failed: java.io.IOException: rule unavailable"))
        assertEquals(
            listOf("first", "last", "peer"),
            service(rules).getDiagnostics(uri, source).map {
                it.message
            }.sorted(),
        )
    }

    @Test
    fun `multiple ordinary failures preserve rule order and original causes`(): Unit = runBlocking {
        val first = IOException("first failure")
        val second = IllegalArgumentException("second failure")
        val provider =
            CustomRulesProvider(listOf(rule("one") { throw first }, rule("two") { throw second }), compilation)

        val aggregate = assertFailsWith<Exception> { provider.provideDiagnostics(uri, source).toList() }

        assertEquals(
            "Custom diagnostic rules failed: one: java.io.IOException: first failure; " +
                "two: java.lang.IllegalArgumentException: second failure",
            aggregate.message,
        )
        assertSame(first, aggregate.cause)
        assertEquals(listOf(second), aggregate.suppressed.toList())
    }

    @Test
    fun `base rule cancellation immediately aborts the provider and collector`(): Unit = runBlocking {
        assertImmediateFailure(CancellationException("rule cancelled"))
    }

    @Test
    fun `base rule fatal failure immediately aborts the provider and collector`(): Unit = runBlocking {
        assertImmediateFailure(LinkageError("rule linkage failed"))
    }

    @Test
    fun `AST accessor failure cannot impersonate a legitimate absent AST`(): Unit = runBlocking {
        every { compilation.getAst(uri) } throws IOException("AST unavailable")

        val result = service(listOf(astRule())).collectDiagnostics(uri, source)

        assertFalse(result.isSuccessful)
        assertTrue(checkNotNull(result.failures.single().message).contains("ast: java.io.IOException: AST unavailable"))
    }

    @Test
    fun `diagnostic accessor failure cannot impersonate syntax errors`(): Unit = runBlocking {
        every { compilation.getDiagnostics(uri) } throws IOException("diagnostics unavailable")

        val result = service(listOf(rule("dependent") { listOf(diagnostic("must not certify")) }))
            .collectDiagnostics(uri, source)

        assertFalse(result.isSuccessful)
        assertEquals(listOf("peer"), result.diagnostics.map { it.message })
        assertTrue(
            checkNotNull(result.failures.single().message)
                .contains("dependent: java.io.IOException: diagnostics unavailable"),
        )
    }

    @Test
    fun `a genuinely absent AST remains a healthy no applicable rule result`(): Unit = runBlocking {
        val result = service(listOf(astRule())).collectDiagnostics(uri, source)

        assertTrue(result.isSuccessful)
        assertEquals(listOf("peer"), result.diagnostics.map { it.message })
    }

    @Test
    fun `genuine syntax errors skip ordinary rules without recording failure`(): Unit = runBlocking {
        every { compilation.getDiagnostics(uri) } returns listOf(
            diagnostic("syntax").apply {
                severity = DiagnosticSeverity.Error
            },
        )
        var analyzed = false

        val result = service(
            listOf(
                rule("skipped") {
                    analyzed = true
                    emptyList()
                },
            ),
        ).collectDiagnostics(uri, source)

        assertFalse(analyzed)
        assertTrue(result.isSuccessful)
        assertEquals(listOf("peer"), result.diagnostics.map { it.message })
    }

    @Test
    fun `disabled rules never become failed outcomes`(): Unit = runBlocking {
        val provider = CustomRulesProvider(
            listOf(rule("disabled") { error("must not run") }),
            compilation,
            DiagnosticRuleConfig(disabledRuleIds = setOf("disabled")),
        )

        val result = DiagnosticsService(listOf(provider)).collectDiagnostics(uri, source)

        assertTrue(result.isSuccessful)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `downstream collection failure is never classified as a rule failure`(): Unit = runBlocking {
        val failure = IOException("downstream failed")
        val provider = CustomRulesProvider(listOf(rule("healthy") { listOf(diagnostic("healthy")) }), compilation)

        assertSame(
            failure,
            assertFailsWith<IOException> {
                provider.provideDiagnostics(uri, source).collect { throw failure }
            },
        )
    }

    private suspend fun assertImmediateFailure(failure: Throwable) {
        val visited = mutableListOf<String>()
        val rules = listOf(
            rule("ordinary-first") {
                visited += "ordinary-first"
                throw IOException("ordinary")
            },
            rule("aborted") {
                visited += "aborted"
                throw failure
            },
            rule("never") {
                visited += "never"
                emptyList()
            },
        )

        val observed = assertFailsWith<Throwable> { service(rules).collectDiagnostics(uri, source) }

        assertEquals(failure.javaClass, observed.javaClass)
        assertEquals(failure.message, observed.message)
        assertEquals(listOf("ordinary-first", "aborted"), visited)
    }

    private fun astRule() = rule("ast") { context ->
        if (context.getAst() == null) emptyList() else listOf(diagnostic("AST result"))
    }

    private fun service(rules: List<DiagnosticRule>): DiagnosticsService {
        val peer = object : StreamingDiagnosticProvider {
            override val id = "healthy-peer"
            override suspend fun provideDiagnostics(uri: URI, content: String) = flowOf(diagnostic("peer"))
        }
        return DiagnosticsService(listOf(CustomRulesProvider(rules, compilation), peer))
    }

    private fun rule(id: String, action: (RuleContext) -> List<Diagnostic>) = object : AbstractDiagnosticRule() {
        override val id = id
        override val description = "Outcome control"
        override suspend fun analyzeImpl(uri: URI, content: String, context: RuleContext) = action(context)
    }

    private fun diagnostic(message: String) = Diagnostic(Range(Position(0, 0), Position(0, 1)), message)
}
