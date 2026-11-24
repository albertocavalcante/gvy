// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.ast

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.gvy.viz.converters.CoreAstConverter
import com.github.albertocavalcante.gvy.viz.converters.NativeAstConverter
import com.github.albertocavalcante.nativeapi.Fallback
import com.github.albertocavalcante.nativeapi.ParseResult
import com.github.albertocavalcante.nativeapi.ParserSeverity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import java.net.URI
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
        withParsed("Ready", source) { parsed, documents, uri ->
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

            assertAvailable(parsed, documents, uri, source)
        }
    }

    @Test
    fun `both projections accept canonical recovery with retained unresolved errors`(): Unit = runBlocking {
        val source = "import spock.lang.Specification\nclass MissingSuper extends Specification {}"
        withParsed("MissingSuper", source) { parsed, documents, uri ->
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

            assertAvailable(parsed, documents, uri, source)
        }
    }

    @Test
    fun `both projections reject a nonstubbable semantic failure before canonicalization`(): Unit = runBlocking {
        val source = "class MissingNested extends Outer.Inner {}"
        withParsed("MissingNested", source) { parsed, documents, uri ->
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

            assertUnavailable(parsed, documents, uri, source)
        }
    }

    @Test
    fun `both projections reject an incomplete canonical transform`(): Unit = runBlocking {
        val source = "@groovy.transform.ToString(includes='missing')\nclass BrokenTransform { String present }"
        withParsed("BrokenTransform", source) { parsed, documents, uri ->
            assertFailedCanonicalTransform(parsed)

            assertUnavailable(parsed, documents, uri, source)
        }
    }

    @Test
    fun `filtering diagnostics cannot complete a failed canonical transform`(): Unit = runBlocking {
        val source = "@groovy.transform.ToString(includes='missing')\nclass FilteredTransform { String present }"
        withParsed("FilteredTransform", source) { failed, documents, uri ->
            assertFailedCanonicalTransform(failed)
            // This controlled projection changes public diagnostics, not the real failed source's completion state.
            val filtered = failed.copy(diagnostics = emptyList())
            assertTrue(filtered.isSuccessful)
            assertTrue(filtered.compilationUnit.errorCollector.hasErrors())
            assertSourcePhase(filtered, Phases.SEMANTIC_ANALYSIS, complete = true)

            assertUnavailable(filtered, documents, uri, source)
        }
    }

    @Test
    fun `successful conversion alone does not satisfy a canonical request`(): Unit = runBlocking {
        val source = "class ConversionOnly {}"
        withParsed("ConversionOnly", source, Phases.CONVERSION) { conversion, documents, uri ->
            assertSourcePhase(conversion, Phases.CONVERSION, complete = true)
            assertEquals(Phases.SEMANTIC_ANALYSIS, conversion.compilationUnit.phase)
            assertFalse(conversion.compilationUnit.isPhaseComplete)
            assertFalse(conversion.compilationUnit.errorCollector.hasErrors())
            assertTrue(conversion.isSuccessful)
            assertTrue(conversion.diagnostics.none { it.severity == ParserSeverity.ERROR })
            assertTrue(conversion.outcome.stubbedClasses.isEmpty())
            assertEquals(Phases.CONVERSION, conversion.outcome.achievedPhase)

            assertUnavailable(conversion, documents, uri, source)

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
            assertUnavailable(optimistic, documents, uri, source)
        }
    }

    @Test
    fun `both projections reject a real compile that fell back below canonicalization`(): Unit = runBlocking {
        val source = "@groovy.util.logging.Slf4j\nclass Broken {}"
        withParsed("Broken", source) { parsed, documents, uri ->
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

            assertUnavailable(parsed, documents, uri, source)
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
        verify: suspend (ParseResult, DocumentProvider, URI) -> Unit,
    ) {
        val uri = URI("file:///$className.groovy")
        val documents = DocumentProvider().apply { put(uri, source, DocumentVersion.of(1)) }
        val compiler = GroovyCompilationService(documentProvider = documents)
        try {
            val parsed = compiler.compileTransient(uri, source, phase)
            try {
                // Emit the actual witness before assumptions so a failed calibration still records useful evidence.
                println(
                    "AST phase probe $className: unit=${parsed.compilationUnit.phase}/" +
                        "${parsed.compilationUnit.isPhaseComplete}, source=${parsed.sourceUnit.phase}/" +
                        "${parsed.sourceUnit.isPhaseComplete}, " +
                        "rawErrors=${parsed.compilationUnit.errorCollector.hasErrors()}, " +
                        "outcome=${parsed.outcome}, diagnostics=${parsed.diagnostics}",
                )
                val ast = assertNotNull(parsed.ast, "Each probe must produce a real AST before endpoint assertions")
                assertEquals(uri.path, parsed.sourceUnit.name)
                assertSame(parsed.sourceUnit.ast, ast)
                assertSame(parsed.sourceUnit, ast.context)
                assertEquals(phase, parsed.outcome.requestedPhase)
                verify(parsed, documents, uri)
            } finally {
                parsed.compilationUnit.classLoader.close()
            }
        } finally {
            compiler.clearCaches()
        }
    }

    private suspend fun assertAvailable(
        parsed: ParseResult,
        documents: DocumentProvider,
        uri: URI,
        source: String,
    ): Unit = supervisorScope {
        val handler = handlerFor(parsed, documents, uri, source, this)
        val snapshot = assertNotNull(documents.getSnapshot(uri))
        for (parser in listOf("native", "core")) {
            val result = handler.getAst(AstParams(uri.toString(), parser)).await()
            assertEquals(parser, result.parser)
            assertEquals(snapshot.version?.value, result.documentVersion)
            assertEquals(snapshot.contentHash, result.sourceHash)
            assertTrue(result.ast.isNotBlank(), parser)
        }
    }

    private suspend fun assertUnavailable(
        parsed: ParseResult,
        documents: DocumentProvider,
        uri: URI,
        source: String,
    ): Unit = supervisorScope {
        val handler = handlerFor(parsed, documents, uri, source, this)
        for (parser in listOf("native", "core")) {
            val error = assertFailsWith<ResponseErrorException> {
                handler.getAst(AstParams(uri.toString(), parser)).await()
            }
            assertEquals(ResponseErrorCode.RequestFailed.value, error.responseError.code, parser)
        }
    }

    private fun handlerFor(
        parsed: ParseResult,
        documents: DocumentProvider,
        uri: URI,
        source: String,
        scope: CoroutineScope,
    ): AstRequestHandler {
        val backend = mockk<GroovyCompilationService>()
        // Feed the exact real compiler result whose source witness was calibrated above.
        coEvery { backend.compileTransient(uri, source, Phases.CANONICALIZATION) } returns parsed
        return AstRequestHandler(backend, CoreAstConverter(), NativeAstConverter(), documents, scope)
    }
}
