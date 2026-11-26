// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.ast

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.gvy.viz.converters.CoreAstConverter
import com.github.albertocavalcante.gvy.viz.converters.NativeAstConverter
import com.github.albertocavalcante.nativeapi.Fallback
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import com.github.albertocavalcante.nativeapi.ParserSeverity
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AstPhaseAvailabilityTest {
    @Test
    fun `both projections accept a completed canonical transform`(): Unit = runBlocking {
        val source = "@groovy.transform.ToString(includes='present')\nclass Ready { String present }"
        withParsed("Ready", source, available = true) { parsed ->
            assertSourcePhase(parsed, Phases.CANONICALIZATION, complete = true)
            assertEquals(Phases.INSTRUCTION_SELECTION, parsed.compilationUnit.phase)
            assertFalse(parsed.compilationUnit.isPhaseComplete)
            assertFalse(parsed.compilationUnit.errorCollector.hasErrors())
            assertTrue(parsed.isSuccessful, parsed.diagnostics.toString())
            assertTrue(parsed.diagnostics.none { it.severity == ParserSeverity.ERROR })
            assertTrue(parsed.outcome.stubbedClasses.isEmpty())
            assertNull(parsed.outcome.fallback)
            assertEquals(Phases.CANONICALIZATION, parsed.outcome.achievedPhase)
            val clazz = assertNotNull(parsed.ast).classes.single()
            assertTrue(
                clazz.methods.any { it.name == "toString" && it.parameters.isEmpty() },
                "The real canonical transform must generate toString before endpoint assertions",
            )

            parsed
        }
    }

    @Test
    fun `both projections accept canonical recovery with retained unresolved errors`(): Unit = runBlocking {
        val source = "import spock.lang.Specification\nclass MissingSuper extends Specification {}"
        withParsed("MissingSuper", source, available = true) { parsed ->
            assertSourcePhase(parsed, Phases.CANONICALIZATION, complete = true)
            assertEquals(Phases.INSTRUCTION_SELECTION, parsed.compilationUnit.phase)
            assertFalse(parsed.compilationUnit.errorCollector.hasErrors())
            assertEquals(listOf("spock.lang.Specification"), parsed.outcome.stubbedClasses)
            assertNull(parsed.outcome.fallback)
            assertEquals(Phases.CANONICALIZATION, parsed.outcome.achievedPhase)
            assertFalse(parsed.isSuccessful, "The original unresolved error remains public after successful recovery")
            assertTrue(
                parsed.diagnostics.any {
                    it.severity == ParserSeverity.ERROR &&
                        it.message.contains("unable to resolve class spock.lang.Specification")
                },
            )
            assertEquals("spock.lang.Specification", assertNotNull(parsed.ast).classes.single().superClass.name)

            parsed
        }
    }

    @Test
    fun `both projections reject a nonstubbable semantic failure before canonicalization`(): Unit = runBlocking {
        val source = "class MissingNested extends Outer.Inner {}"
        withParsed("MissingNested", source) { parsed ->
            assertSourcePhase(parsed, Phases.CONVERSION, complete = true)
            assertEquals(Phases.SEMANTIC_ANALYSIS, parsed.compilationUnit.phase)
            assertFalse(parsed.compilationUnit.isPhaseComplete)
            assertTrue(parsed.compilationUnit.errorCollector.hasErrors())
            assertTrue(parsed.outcome.stubbedClasses.isEmpty(), "Nested-looking missing types must not be stubbed")
            assertNull(parsed.outcome.fallback)
            assertEquals(Phases.CANONICALIZATION, parsed.outcome.achievedPhase)
            assertFalse(parsed.isSuccessful)
            assertTrue(
                parsed.diagnostics.any {
                    it.severity == ParserSeverity.ERROR && it.message.contains("unable to resolve class Outer.Inner")
                },
            )

            parsed
        }
    }

    @Test
    fun `both projections reject an incomplete canonical transform`(): Unit = runBlocking {
        val source = "@groovy.transform.ToString(includes='missing')\nclass BrokenTransform { String present }"
        withParsed("BrokenTransform", source) { parsed ->
            assertFailedCanonicalTransform(parsed)

            parsed
        }
    }

    @Test
    fun `filtering diagnostics cannot complete a failed canonical transform`(): Unit = runBlocking {
        val source = "@groovy.transform.ToString(includes='missing')\nclass FilteredTransform { String present }"
        withParsed("FilteredTransform", source) { failed ->
            assertFailedCanonicalTransform(failed)
            // This controlled projection changes public diagnostics, not the real failed source's completion state.
            val filtered = failed.copy(diagnostics = emptyList())
            assertTrue(filtered.isSuccessful)
            assertTrue(filtered.compilationUnit.errorCollector.hasErrors())
            assertSourcePhase(filtered, Phases.SEMANTIC_ANALYSIS, complete = true)

            filtered
        }
    }

    @Test
    fun `successful conversion alone does not satisfy a canonical request`(): Unit = runBlocking {
        val source = "class ConversionOnly {}"
        for (optimisticLabels in listOf(false, true)) {
            withParsed("ConversionOnly", source, Phases.CONVERSION) { conversion ->
                assertSourcePhase(conversion, Phases.CONVERSION, complete = true)
                assertEquals(Phases.SEMANTIC_ANALYSIS, conversion.compilationUnit.phase)
                assertFalse(conversion.compilationUnit.isPhaseComplete)
                assertFalse(conversion.compilationUnit.errorCollector.hasErrors())
                assertTrue(conversion.isSuccessful)
                assertTrue(conversion.diagnostics.none { it.severity == ParserSeverity.ERROR })
                assertTrue(conversion.outcome.stubbedClasses.isEmpty())
                assertEquals(Phases.CONVERSION, conversion.outcome.achievedPhase)

                if (optimisticLabels) {
                    // Model the optimistic phase labels that caught failures can retain; compiler state stays real.
                    val optimistic = conversion.copy(
                        outcome = conversion.outcome.copy(
                            requestedPhase = Phases.CANONICALIZATION,
                            achievedPhase = Phases.CANONICALIZATION,
                        ),
                    )
                    assertTrue(optimistic.isSuccessful)
                    assertFalse(optimistic.compilationUnit.errorCollector.hasErrors())
                    assertSourcePhase(optimistic, Phases.CONVERSION, complete = true)
                    optimistic
                } else {
                    conversion
                }
            }
        }
    }

    @Test
    fun `both projections reject a real compile that fell back below canonicalization`(): Unit = runBlocking {
        val source = "@groovy.util.logging.Slf4j\nclass Broken {}"
        withParsed("Broken", source) { parsed ->
            assertSourcePhase(parsed, Phases.CONVERSION, complete = true)
            assertFalse(parsed.compilationUnit.errorCollector.hasErrors())
            assertTrue(parsed.outcome.stubbedClasses.isEmpty())
            assertEquals(Fallback.COMPILATION_FAILED, parsed.outcome.fallback)
            assertEquals(Phases.CONVERSION, parsed.outcome.achievedPhase)
            assertTrue(
                parsed.diagnostics.any {
                    it.severity == ParserSeverity.ERROR && it.message.contains("slf4j", ignoreCase = true)
                },
            )

            parsed
        }
    }

    private fun assertFailedCanonicalTransform(parsed: ParseResult) {
        assertSourcePhase(parsed, Phases.SEMANTIC_ANALYSIS, complete = true)
        assertEquals(Phases.CANONICALIZATION, parsed.compilationUnit.phase)
        assertFalse(parsed.compilationUnit.isPhaseComplete)
        assertTrue(parsed.compilationUnit.errorCollector.hasErrors())
        assertTrue(parsed.outcome.stubbedClasses.isEmpty())
        assertNull(parsed.outcome.fallback)
        assertEquals(Phases.CANONICALIZATION, parsed.outcome.achievedPhase)
        assertFalse(parsed.isSuccessful)
        assertTrue(
            parsed.diagnostics.any {
                it.severity == ParserSeverity.ERROR &&
                    it.message.contains("includes") &&
                    it.message.contains("missing") &&
                    it.message.contains("does not exist")
            },
        )
    }

    private fun assertSourcePhase(parsed: ParseResult, phase: Int, complete: Boolean) {
        assertEquals(phase, parsed.sourceUnit.phase, "Requested source phase")
        assertEquals(complete, parsed.sourceUnit.isPhaseComplete, "Requested source completion")
    }

    private suspend fun withParsed(
        className: String,
        source: String,
        phase: Int = Phases.CANONICALIZATION,
        available: Boolean = false,
        project: (ParseResult) -> ParseResult,
    ): Unit = supervisorScope {
        val uri = URI("file:///$className.groovy")
        val documents = DocumentProvider().apply { put(uri, source, DocumentVersion.of(1)) }
        val snapshot = assertNotNull(documents.getSnapshot(uri))
        val calls = AtomicInteger()
        val compiler = GroovyCompilationService(documentProvider = documents, workerSessionDecorator = { delegate ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult {
                    assertEquals(uri, request.uri)
                    assertEquals(source, request.content)
                    assertEquals(Phases.CANONICALIZATION, request.compilePhase)
                    val parsed = delegate.parse(request.copy(compilePhase = phase))
                    var transferred = false
                    try {
                        val ast = assertNotNull(parsed.ast, "Each probe must produce a real AST")
                        assertEquals(uri.path, parsed.sourceUnit.name)
                        assertSame(parsed.sourceUnit.ast, ast)
                        assertSame(parsed.sourceUnit, ast.context)
                        assertEquals(phase, parsed.outcome.requestedPhase)
                        return project(parsed).also {
                            calls.incrementAndGet()
                            transferred = true
                        }
                    } finally {
                        if (!transferred) parsed.compilationUnit.classLoader.close()
                    }
                }
            }
        })
        try {
            val handler = AstRequestHandler(compiler, CoreAstConverter(), NativeAstConverter(), documents, this)
            for ((index, parser) in listOf("native", "core").withIndex()) {
                val pending = handler.getAst(AstParams(uri.toString(), parser))
                if (available) {
                    val result = pending.await()
                    assertEquals(parser, result.parser)
                    assertEquals(snapshot.version?.value, result.documentVersion)
                    assertEquals(snapshot.contentHash, result.sourceHash)
                    assertTrue(result.ast.isNotBlank(), parser)
                } else {
                    val error = assertFailsWith<ResponseErrorException> { pending.await() }
                    assertEquals(ResponseErrorCode.RequestFailed.value, error.responseError.code, parser)
                    assertEquals("Canonical AST is unavailable: $uri", error.responseError.message, parser)
                }
                assertEquals(index + 1, calls.get(), "Each projection must consume its own inspected worker result")
            }
        } finally {
            compiler.shutdown()
        }
    }
}
