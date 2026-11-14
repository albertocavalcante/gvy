// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import groovy.transform.CompileStatic
import io.mockk.coEvery
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceCommandLifecycleTest {
    @TempDir
    lateinit var root: Path

    private val uri = URI.create("file:///source-actions/Money.groovy")
    private val content = "import groovy.transform.CompileStatic\n@CompileStatic class Money {}"

    @Test
    fun `resolve and attach reject an equal-version reopen while awaiting compilation`(): Unit = runBlocking {
        val resolved = reopenedWhileCompiling { commands, _ ->
            commands.resolve(
                SourceResolveParams(uri.toString(), Position(1, 2), allowDownload = true, documentVersion = 1),
            )
        }
        val attached = reopenedWhileCompiling { commands, _ ->
            commands.attach(
                SourceAttachParams(
                    uri.toString(),
                    Position(1, 2),
                    root.resolve("sources.jar").toUri().toString(),
                    documentVersion = 1,
                ),
            )
        }
        assertEquals(SourceResolveStatus.STALE, resolved.status)
        assertEquals(SourceResolveStatus.STALE, attached.status)
    }

    @Test
    fun `a browser RPC cannot rebind to an equal-version reopened original document`(): Unit = runBlocking {
        val session = reopenedWhileCompiling { commands, descriptor -> commands.openLookupSession(descriptor) }
        assertNull(session)
    }

    private suspend fun <T> reopenedWhileCompiling(
        operation: suspend (SourceCommandService, SourceDocumentDescriptor) -> T,
    ): T = withTimeout(10_000) {
        val documents = DocumentProvider().apply { put(uri, content, 1) }
        val compilation = spyk(
            GroovyCompilationService(
                parentClassLoader = CompileStatic::class.java.classLoader,
                documentProvider = documents,
            ),
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val navigator = recordingNavigator(calls)
        try {
            assertTrue(compilation.compile(uri, content).isSuccess)
            val request = assertNotNull(compilation.captureSourceRequest(uri, "java.lang.String"))
            val descriptor = SourceDocumentDescriptor(
                sourceDigest = "a".repeat(64),
                contentDigest = "b".repeat(64),
                entryName = "java/lang/String.java",
                contentPath = root.resolve("String.java"),
                origin = request.symbol.origin,
                context = request.context.identity,
                displayName = "JDK",
                languageId = "java",
            )
            coEvery { compilation.ensureCompiled(uri) } coAnswers {
                entered.complete(Unit)
                release.await()
                compilation.compile(uri, content)
            }
            coEvery { compilation.ensureCompiled(uri, any()) } coAnswers {
                entered.complete(Unit)
                release.await()
                compilation.compile(uri, content)
            }
            val commands = SourceCommandService(
                compilation,
                documents,
                navigator,
                ExternalSourceRegistry(root.resolve("browser")),
            )
            supervisorScope {
                val pending = async { operation(commands, descriptor) }
                entered.await()
                documents.remove(uri)
                documents.put(uri, content, 1)
                release.complete(Unit)
                pending.await().also {
                    assertTrue(
                        calls.isEmpty(),
                        "A reopened document must not receive the earlier action's source acquisition",
                    )
                }
            }
        } finally {
            release.complete(Unit)
            compilation.shutdown()
        }
    }

    private fun recordingNavigator(calls: MutableList<String>): SourceNavigator = object : SourceNavigator {
        override suspend fun locate(symbol: ResolvedExternalSymbol, context: SourceLookupContext): SourceLookupResult {
            calls.add(symbol.binaryName)
            return SourceLookupResult.Unavailable(MissingSourceReason.NO_ATTACHMENT)
        }

        override suspend fun attachSource(
            symbol: ResolvedExternalSymbol,
            context: SourceLookupContext,
            sourceJar: Path,
            expectedSha256: String?,
        ): SourceLookupResult = locate(symbol, context)

        override suspend fun navigateToSource(classpathUri: URI, className: String): SourceNavigator.SourceResult =
            error("Unexpected legacy navigation")

        override suspend fun navigateToMethodSource(
            classpathUri: URI,
            className: String,
            methodName: String,
        ): SourceNavigator.SourceResult = error("Unexpected legacy navigation")
    }
}
