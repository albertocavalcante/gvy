// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.compilation.SignatureCompletionRequest
import com.github.albertocavalcante.gvy.gls.providers.SignatureHelpProvider
import com.github.albertocavalcante.gvy.gls.providers.completion.CompletionProvider
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.gvy.source.DocumentVersion
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SignatureCompletionBinaryInputsTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `signature rejects an equal-size restored-time jar after projecting an inherited method`() =
        verify(archive = true)

    @Test
    fun `completion rejects changed class bytes in a source root after closing its derived loader`() =
        verify(archive = false)

    private fun verify(archive: Boolean): Unit = runBlocking {
        val binary = RotatingMemberBinary(temp, archive)
        val project = Files.createDirectory(temp.resolve("workspace"))
        val expected = if (archive) "String receiptCall(String)" else "receiptAlpha"
        val replacement = if (archive) "Double receiptCall(String)" else "receiptBravo"
        Fixture(project, binary, expectedReplacement = false).use { stable ->
            assertTrue(expected in stable.wire(), "The wire control must return the physical member")
            assertTrue(stable.proven.get(), "The real parser must select the binary hierarchy")
        }

        // This producer receives no extra test reads. Mutate after the same provider as the wire has returned.
        Fixture(project, binary, expectedReplacement = null).use { held ->
            val request = held.request()
            val error = assertFailsWith<ResponseErrorException> {
                request.deliver {
                    val labels = if (archive) {
                        SignatureHelpProvider.provideSignatureHelp(request).signatures.map { it.label }
                    } else {
                        CompletionProvider.getContextualCompletions(request).map { it.label }
                    }
                    assertTrue(expected in labels, "Expected $expected, got $labels")
                    binary.replace()
                    labels
                }
            }
            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code)
        }
        Fixture(project, binary, expectedReplacement = true).use { fresh ->
            val labels = fresh.wire()
            assertTrue(replacement in labels, "Expected $replacement, got $labels")
            assertFalse(expected in labels)
            assertTrue(fresh.proven.get())
        }
    }

    private class Fixture(
        project: Path,
        private val binary: RotatingMemberBinary,
        private val expectedReplacement: Boolean?,
    ) : AutoCloseable {
        val proven = AtomicBoolean()
        private val signature = binary.archive
        private val expression = if (signature) "new Subject().receiptCall('x')" else "new Subject().receipt"
        private val source = "class Subject extends dep.Base {}\n$expression"
        private val position = Position(1, if (signature) expression.indexOf("'x'") else expression.length)
        private val src = Files.createDirectories(project.resolve("src"))
        private val uri = Files.writeString(src.resolve("Client.groovy"), source).toUri()
        private val documents = DocumentProvider()
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private val compilation = GroovyCompilationService(
            documentProvider = documents,
            workerSessionDecorator = { delegate ->
                object : WorkerSession {
                    override fun parse(request: ParseRequest): ParseResult = delegate.parse(request).also { parsed ->
                        val relevant = signature || CompletionProvider.DUMMY_IDENTIFIER in request.content
                        if (expectedReplacement != null && relevant) {
                            verifyBinding(parsed, expectedReplacement)
                        }
                    }
                }
            },
        )
        private val service = GroovyTextDocumentService(
            scope,
            compilation,
            GroovyTextDocumentServiceOptions(documentProvider = documents),
        )

        init {
            val sdk = Path.of(groovy.lang.GroovyObject::class.java.protectionDomain.codeSource.location.toURI())
            val classpath = if (signature) listOf(binary.root, sdk) else listOf(sdk)
            val roots = if (signature) listOf(src) else listOf(src, binary.root)
            compilation.updateWorkspaceModel(project, classpath, roots)
            compilation.onDocumentOpened(uri)
            documents.put(uri, source, DocumentVersion.of(1))
        }

        private fun verifyBinding(parsed: ParseResult, replacement: Boolean) {
            val subject = assertNotNull(parsed.ast).classes.single { it.name == "Subject" }
            val base = subject.superClass.redirect()
            assertFalse(base.isPrimaryClassNode)
            assertTrue(base.isResolved)
            val owner = base.superClass.redirect()
            assertEquals("dep.Parent", owner.name)
            val method = owner.getDeclaredMethods("receiptCall").single()
            assertEquals(if (replacement) "java.lang.Double" else "java.lang.String", method.returnType.name)
            val loader = parsed.compilationUnit.classLoader
            val loaded = loader.loadClass("dep.Base", false, true, false)
            assertSame(loader, loaded.classLoader)
            binary.assertBinding(loaded, replacement)
            proven.set(true)
        }

        fun request() = SignatureCompletionRequest(compilation, documents, uri, position, completion = !signature)

        suspend fun wire(): List<String> {
            val document = TextDocumentIdentifier(uri.toString())
            if (signature) {
                val result = service.signatureHelp(SignatureHelpParams(document, position)).await()
                return result.signatures.map { it.label }
            }
            val answer = service.completion(CompletionParams(document, position)).await()
            return (if (answer.isLeft) answer.left else answer.right.items).map { it.label }
        }

        override fun close() {
            scope.cancel()
            compilation.shutdown()
        }
    }
}
