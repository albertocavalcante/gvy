// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import groovy.transform.CompileStatic
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SourceCommandServiceTest {
    @TempDir
    lateinit var root: Path

    private val uri = URI.create("file:///source-actions/Money.groovy")
    private val content = "import groovy.transform.CompileStatic\n@CompileStatic class Money {}"
    private val position = Position(1, 2)
    private val sourceUri = URI.create("file:///sources/CompileStatic.java")
    private val definition = SourceDefinition(SourceSpan(3, 0, 6, 1), SourceSpan(4, 18, 4, 31))

    @Test
    fun `downloads require the explicit action policy and resolve the annotation type`(): Unit = runBlocking {
        withCommands { fixture ->
            fixture.commands.resolve(resolve())
            fixture.commands.resolve(resolve().copy(allowDownload = true))

            assertEquals(
                listOf(SourceLookupPolicy.LOCAL_ONLY, SourceLookupPolicy.ALLOW_VERIFIED_DOWNLOADS),
                fixture.navigator.calls.map { it.policy },
            )
            assertEquals(
                listOf("groovy.transform.CompileStatic", "groovy.transform.CompileStatic"),
                fixture.navigator.calls.map { it.symbol.binaryName },
            )
        }
    }

    @Test
    fun `stale resolve and attach versions perform no source work`(): Unit = runBlocking {
        withCommands { fixture ->
            fixture.documents.update(uri, content, 2)

            assertEquals(SourceResolveStatus.STALE, fixture.commands.resolve(resolve()).status)
            assertEquals(SourceResolveStatus.STALE, fixture.commands.attach(attach()).status)
            assertTrue(fixture.navigator.calls.isEmpty())
        }
    }

    @Test
    fun `attachment passes the selected local archive and digest without granting downloads`(): Unit = runBlocking {
        withCommands { fixture ->
            val request = attach().copy(expectedSha256 = "a".repeat(64))

            fixture.commands.attach(request)

            val call = fixture.navigator.calls.single()
            assertEquals(root.resolve("sources.jar"), call.attachment)
            assertEquals(request.expectedSha256, call.sha256)
            assertEquals(SourceLookupPolicy.LOCAL_ONLY, call.policy)
            assertEquals("groovy.transform.CompileStatic", call.symbol.binaryName)
        }
    }

    @Test
    fun `a document change during download or attachment discards the located result`(): Unit = runBlocking {
        withCommands { fixture ->
            fixture.navigator.answer = { symbol, _ ->
                fixture.documents.update(uri, content, 2)
                SourceLookupResult.Located(sourceUri, symbol.binaryName, definition)
            }
            assertEquals(
                SourceResolveStatus.STALE,
                fixture.commands.resolve(resolve().copy(allowDownload = true)).status,
            )

            assertTrue(fixture.compilation.compile(uri, content).isSuccess)
            fixture.navigator.answer = { symbol, _ ->
                fixture.documents.remove(uri)
                fixture.documents.put(uri, content, 2)
                SourceLookupResult.Located(sourceUri, symbol.binaryName, definition)
            }
            assertEquals(SourceResolveStatus.STALE, fixture.commands.attach(attach().copy(documentVersion = 2)).status)
        }
    }

    @Test
    fun `a located source preserves its declaration and exact name selection`(): Unit = runBlocking {
        withCommands { fixture ->
            fixture.navigator.answer = { symbol, _ ->
                SourceLookupResult.Located(sourceUri, symbol.binaryName, definition)
            }

            val result = fixture.commands.resolve(resolve())

            assertEquals(SourceResolveStatus.LOCATED, result.status)
            val location = result.locations.single()
            assertEquals(sourceUri.toString(), location.targetUri)
            assertEquals(Position(3, 0), location.targetRange.start)
            assertEquals(Position(6, 1), location.targetRange.end)
            assertEquals(Position(4, 18), location.targetSelectionRange.start)
            assertEquals(Position(4, 31), location.targetSelectionRange.end)
        }
    }

    @Test
    fun `source files without a declaration remain openable without invented locations`(): Unit = runBlocking {
        withCommands { fixture ->
            fixture.navigator.answer = { symbol, _ ->
                SourceLookupResult.FileOnly(sourceUri, symbol.binaryName, MissingSourceReason.DECLARATION_NOT_LOCATED)
            }

            val result = fixture.commands.resolve(resolve())

            assertEquals(SourceResolveStatus.FILE_ONLY, result.status)
            assertEquals(sourceUri.toString(), result.fileUri)
            assertTrue(result.locations.isEmpty())
        }
    }

    @Test
    fun `repository binary mismatch explains the need for matching attached sources`(): Unit = runBlocking {
        withCommands { fixture ->
            fixture.navigator.answer = { _, _ ->
                SourceLookupResult.Unavailable(MissingSourceReason.BINARY_ORIGIN_MISMATCH)
            }

            val result = fixture.commands.resolve(resolve().copy(allowDownload = true))

            assertEquals(SourceResolveStatus.UNAVAILABLE, result.status)
            assertEquals("BINARY_ORIGIN_MISMATCH", result.reason)
            assertContains(assertNotNull(result.message), "does not match")
            assertContains(assertNotNull(result.message), "Attach matching sources")
            assertTrue(result.locations.isEmpty())
        }
    }

    @Test
    fun `integrity failure ambiguity and stale context do not become missing sources`(): Unit = runBlocking {
        withCommands { fixture ->
            val outcomes = listOf(
                SourceLookupResult.Failed(SourceFailure.INTEGRITY_MISMATCH) to SourceResolveStatus.FAILED,
                SourceLookupResult.Ambiguous to SourceResolveStatus.AMBIGUOUS,
                SourceLookupResult.Stale to SourceResolveStatus.STALE,
            )
            for ((outcome, expected) in outcomes) {
                fixture.navigator.answer = { _, _ -> outcome }
                val result = fixture.commands.resolve(resolve())
                assertEquals(expected, result.status)
                assertTrue(result.locations.isEmpty())
                if (outcome is SourceLookupResult.Failed) assertEquals("INTEGRITY_MISMATCH", result.reason)
            }
        }
    }

    @Test
    fun `cancellation during source acquisition preserves cancellation`(): Unit = runBlocking {
        withCommands { fixture ->
            val cancellation = CancellationException("request cancelled")
            fixture.navigator.answer = { _, _ -> throw cancellation }

            assertSame(cancellation, assertFailsWith<CancellationException> { fixture.commands.resolve(resolve()) })
            assertSame(cancellation, assertFailsWith<CancellationException> { fixture.commands.attach(attach()) })
        }
    }

    @Test
    fun `browser sessions retain the original parse across later document recompilation`(): Unit = runBlocking {
        withCommands { fixture ->
            val request = assertNotNull(fixture.compilation.captureSourceRequest(uri, "groovy.transform.CompileStatic"))
            val descriptor = SourceDocumentDescriptor(
                sourceDigest = "a".repeat(64),
                contentDigest = "b".repeat(64),
                entryName = "groovy/transform/CompileStatic.java",
                contentPath = root.resolve("content.java"),
                origin = request.symbol.origin,
                context = request.context.identity,
                displayName = "Groovy",
                languageId = "java",
            )
            val session = assertNotNull(fixture.commands.openLookupSession(descriptor))
            fixture.navigator.answer = { symbol, _ ->
                SourceLookupResult.Located(sourceUri, symbol.binaryName, definition)
            }
            assertTrue(session.isCurrent())

            fixture.documents.update(uri, content, 2)
            assertTrue(fixture.compilation.compile(uri, content).isSuccess)

            assertEquals(SourceLookupResult.Stale, session.locateType("groovy.transform.CompileStatic"))
            assertTrue(fixture.navigator.calls.isEmpty())
        }
    }

    @Test
    fun `dependency source actions cannot replace the selected workspace annotation with a same-FQN SDK binary`() =
        runBlocking {
            withCommands { fixture ->
                val selected = URI.create("file:///workspace/groovy/transform/CompileStatic.groovy")
                fixture.documents.put(selected, "package groovy.transform\n@interface CompileStatic {}", 1)
                fixture.compilation.clearCaches()
                assertTrue(fixture.compilation.compile(uri, content).isSuccess)
                val held = assertNotNull(fixture.compilation.getCompiledSnapshot(uri)).parseResult
                assertEquals(selected.path, held.sourceAnnotations.headAt(1, 2)?.sourceUnitName)

                val imported = Position(0, content.indexOf("CompileStatic") + 1)
                val results = listOf(
                    fixture.commands.resolve(resolve().copy(allowDownload = true)),
                    fixture.commands.resolve(resolve().copy(position = imported, allowDownload = true)),
                    fixture.commands.attach(attach()),
                    fixture.commands.attach(attach().copy(position = imported)),
                )

                assertTrue(results.all { it.status == SourceResolveStatus.UNAVAILABLE })
                assertTrue(
                    fixture.navigator.calls.isEmpty(),
                    "No download or attachment may target the unselected SDK type",
                )
            }
        }

    @Test
    fun `static source annotation imports cannot download or attach the owner binary`(): Unit = runBlocking {
        withCommands { fixture ->
            val nested = "import static sample.Outer.Mark\n@Mark class Money {}"
            val ownerUri = URI.create("file:///workspace/sample/Outer.groovy")
            fixture.documents.put(
                ownerUri,
                "package sample\nclass Outer { static @interface Mark {} }",
                1,
            )
            assertNotNull(fixture.documents.update(uri, nested, 2))
            fixture.compilation.clearCaches()
            assertTrue(fixture.compilation.compile(uri, nested).isSuccess)
            val held = assertNotNull(fixture.compilation.getCompiledSnapshot(uri)).parseResult
            val imported = assertNotNull(held.ast).staticImports.values.single()
            val annotation = assertNotNull(held.sourceAnnotations.annotationImportedBy(imported))
            assertEquals("sample.Outer\$Mark", annotation.binaryName)
            assertEquals(ownerUri.path, annotation.sourceUnitName)
            val importPosition = Position(0, nested.indexOf("Mark"))

            val results = mapOf(
                "resolve import" to fixture.commands.resolve(
                    resolve().copy(position = importPosition, documentVersion = 2, allowDownload = true),
                ),
                "resolve annotation" to fixture.commands.resolve(
                    resolve().copy(documentVersion = 2, allowDownload = true),
                ),
                "attach import" to fixture.commands.attach(
                    attach().copy(position = importPosition, documentVersion = 2),
                ),
                "attach annotation" to fixture.commands.attach(attach().copy(documentVersion = 2)),
            )

            results.forEach { (action, result) ->
                assertEquals(SourceResolveStatus.UNAVAILABLE, result.status, "$action returned $result")
            }
            assertTrue(
                fixture.navigator.calls.isEmpty(),
                "Neither selected source Mark nor its source owner is an external binary",
            )
        }
    }

    @Test
    fun `source action resolves a binary constructor after non BMP text`(): Unit = runBlocking {
        withCommands { fixture ->
            val line = "class Money { /*😀😀😀😀😀😀😀😀😀😀*/ def n = new BigDecimal('1'); def later = 3 }"
            val source = "import java.math.BigDecimal\n$line"
            assertNotNull(fixture.documents.update(uri, source, 2))
            fixture.compilation.clearCaches()
            assertTrue(fixture.compilation.compile(uri, source).isSuccess)

            val result = fixture.commands.resolve(
                resolve().copy(position = Position(1, line.indexOf("BigDecimal") + 1), documentVersion = 2),
            )

            assertEquals(SourceResolveStatus.UNAVAILABLE, result.status)
            assertEquals("java.math.BigDecimal", fixture.navigator.calls.single().symbol.binaryName)
        }
    }

    @Test
    fun `source actions do not replace a workspace constructor with a same FQN binary`(): Unit = runBlocking {
        withCommands { fixture ->
            val selected = URI.create("file:///workspace/groovy/transform/CompileStatic.groovy")
            fixture.documents.put(selected, "package groovy.transform\nclass CompileStatic {}", 1)
            val line = "class Money { def value = new CompileStatic() }"
            val source = "import groovy.transform.CompileStatic\n$line"
            assertNotNull(fixture.documents.update(uri, source, 2))
            fixture.compilation.clearCaches()
            assertTrue(fixture.compilation.compile(uri, source).isSuccess)
            val held = assertNotNull(fixture.compilation.getCompiledSnapshot(uri)).parseResult
            val cursor = Position(1, line.indexOf("CompileStatic") + 1)
            val node = held.astModel.getNodeAt(
                uri,
                com.github.albertocavalcante.groovyparser.ast.types.Position(cursor.line, cursor.character),
            )
            val type = when (node) {
                is ClassNode -> node
                is ClassExpression -> node.type
                is ConstructorCallExpression -> node.type
                is VariableExpression -> node.type
                else -> error("Expected the selected constructor type, got $node")
            }
            assertEquals("groovy.transform.CompileStatic", type.redirect().name)
            assertTrue(type.redirect().isPrimaryClassNode, "The fixture must select the workspace declaration")

            val results = listOf(
                fixture.commands.resolve(resolve().copy(position = cursor, documentVersion = 2, allowDownload = true)),
                fixture.commands.attach(attach().copy(position = cursor, documentVersion = 2)),
            )
            assertTrue(results.all { it.status == SourceResolveStatus.UNAVAILABLE })
            assertTrue(fixture.navigator.calls.isEmpty())
        }
    }

    private fun resolve() = SourceResolveParams(uri.toString(), position, documentVersion = 1)

    private fun attach() = SourceAttachParams(
        uri.toString(),
        position,
        root.resolve("sources.jar").toUri().toString(),
        documentVersion = 1,
    )

    private suspend fun withCommands(block: suspend (Fixture) -> Unit) {
        val documents = DocumentProvider().apply { put(uri, content, 1) }
        val navigator = RecordingNavigator()
        val compilation = GroovyCompilationService(
            parentClassLoader = CompileStatic::class.java.classLoader,
            documentProvider = documents,
        )
        try {
            assertTrue(compilation.compile(uri, content).isSuccess)
            val commands = SourceCommandService(
                compilation,
                documents,
                navigator,
                ExternalSourceRegistry(root.resolve("views")),
            )
            block(Fixture(documents, compilation, navigator, commands))
        } finally {
            compilation.shutdown()
        }
    }

    private data class Fixture(
        val documents: DocumentProvider,
        val compilation: GroovyCompilationService,
        val navigator: RecordingNavigator,
        val commands: SourceCommandService,
    )

    private class RecordingNavigator : SourceNavigator {
        data class Call(
            val symbol: ResolvedExternalSymbol,
            val policy: SourceLookupPolicy,
            val attachment: Path? = null,
            val sha256: String? = null,
        )

        val calls = mutableListOf<Call>()
        var answer: suspend (ResolvedExternalSymbol, SourceLookupContext) -> SourceLookupResult = { _, _ ->
            SourceLookupResult.Unavailable(MissingSourceReason.NO_ATTACHMENT)
        }

        override suspend fun retrySource(
            symbol: ResolvedExternalSymbol,
            context: SourceLookupContext,
        ): SourceLookupResult = locate(symbol, context)

        override suspend fun locate(symbol: ResolvedExternalSymbol, context: SourceLookupContext): SourceLookupResult {
            calls.add(Call(symbol, context.policy))
            return answer(symbol, context)
        }

        override suspend fun attachSource(
            symbol: ResolvedExternalSymbol,
            context: SourceLookupContext,
            sourceJar: Path,
            expectedSha256: String?,
        ): SourceLookupResult {
            calls.add(Call(symbol, context.policy, sourceJar, expectedSha256))
            return answer(symbol, context)
        }

        override suspend fun navigateToSource(classpathUri: URI, className: String): SourceNavigator.SourceResult =
            error("Unexpected legacy navigation")

        override suspend fun navigateToMethodSource(
            classpathUri: URI,
            className: String,
            methodName: String,
        ): SourceNavigator.SourceResult = error("Unexpected legacy navigation")
    }
}
