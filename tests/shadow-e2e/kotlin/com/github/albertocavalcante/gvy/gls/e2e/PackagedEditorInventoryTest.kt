// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import com.github.albertocavalcante.gvy.gls.services.DocumentAnalysisNotification
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PackagedEditorInventoryTest {
    @Test
    fun `retiring external Jenkins buffers restores exact ordinary workspace incoming calls`(@TempDir root: Path) {
        withServer(root) { session, workspace ->
            val jenkinsFiles = openJenkinsFixtures(session, root)
            val openFiles = openGroovyFixtures(session, workspace)
            val origin = workspace.resolve("Implementation.groovy")
            val first = prepare(session, origin)
            val failure = assertFailsWith<ExecutionException> {
                session.server.textDocumentService.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(first)).await()
            }
            val response = assertIs<ResponseErrorException>(failure.cause).responseError
            assertEquals(ResponseErrorCode.RequestFailed.value, response.code)
            val openUris = (jenkinsFiles + openFiles).map { it.toUri() }
            assertTrue(
                openUris.any { response.message == "Reference index combines different compiler environments at $it" },
                response.message,
            )

            for (path in jenkinsFiles) {
                val id = TextDocumentIdentifier(path.toUri().toString())
                session.server.textDocumentService.didClose(DidCloseTextDocumentParams(id))
            }
            assertRoundTrip(session, origin)
        }
    }

    @Test
    fun `the editor fixture inventory resolves exact incoming calls in a fresh server`(@TempDir root: Path) {
        withServer(root) { session, workspace ->
            openGroovyFixtures(session, workspace)
            assertRoundTrip(session, workspace.resolve("Implementation.groovy"))
        }
    }

    private fun withServer(root: Path, check: (LanguageServerSession, Path) -> Unit) {
        val workspace = Files.createDirectory(root.resolve("fixtures"))
        val fixtureRoot = repository().resolve("editors/code/client/src/test/fixtures")
        Files.newDirectoryStream(fixtureRoot).use { paths ->
            for (path in paths) Files.copy(path, workspace.resolve(path.fileName))
        }
        val jar = Path.of(checkNotNull(System.getProperty("groovy.lsp.e2e.execJar"))).toRealPath()
        LanguageServerSessionFactory().start(ServerConfig(), "packaged-editor-inventory").use { session ->
            val args = assertNotNull(session.process).info().arguments().orElseThrow().toList()
            val jarFlag = args.indexOf("-jar")
            assertTrue(jarFlag >= 0)
            assertEquals(jar, Path.of(args[jarFlag + 1]).toRealPath())
            session.server.initialize(
                InitializeParams().apply {
                    capabilities = ClientCapabilities().apply { experimental = mapOf("groovyDocumentAnalysis" to true) }
                    workspaceFolders = listOf(WorkspaceFolder(workspace.toUri().toString(), "fixtures"))
                },
            ).await()
            session.server.initialized(InitializedParams())
            session.client.readyFuture.await()
            check(session, workspace)
            session.server.shutdown().await()
            session.exit()
        }
    }

    private fun repository(): Path {
        val scenarios = Path.of(checkNotNull(System.getProperty("groovy.lsp.e2e.scenarioDir")))
        return scenarios.resolve("../../../..").normalize()
    }

    private fun openJenkinsFixtures(session: LanguageServerSession, root: Path): List<Path> {
        val fixtures = repository().resolve("gls/src/test/resources/fixtures/jenkins-declarative")
        return listOf("blank", "partial", "arguments", "blank", "partial", "blank").mapIndexed { index, site ->
            val text = Files.readString(fixtures.resolve("parameters-$site.Jenkinsfile")).replace("/*caret*/", "")
            val path = Files.createTempDirectory(root, "gvy-jenkins-authoring-").resolve("Jenkinsfile")
            Files.writeString(path, text)
            val language = if (index == 5) "groovy" else "jenkinsfile"
            openAnalyzed(session, path, language)
            // The integration suite removes each temp file after closing its tab. Its text model is still open.
            Files.delete(path)
            path
        }
    }

    private fun openGroovyFixtures(session: LanguageServerSession, workspace: Path): List<Path> =
        listOf("Implementation", "CodeNarcRanges", "SemanticTokens", "FoldingRanges", "SelectionRanges",
            "ScriptClassLiterals", "ImportedAliases").map { name ->
            workspace.resolve("$name.groovy").also { openAnalyzed(session, it, "groovy") }
        }

    private fun openAnalyzed(session: LanguageServerSession, path: Path, language: String) {
        val uri = path.toUri().toString()
        val item = TextDocumentItem(uri, language, 1, Files.readString(path))
        session.server.textDocumentService.didOpen(DidOpenTextDocumentParams(item))
        val result = session.client.awaitNotificationStrict("groovy/documentAnalysis", TIMEOUT_MS, { value ->
            value is DocumentAnalysisNotification && value.uri == uri && value.version == 1
        })
        val analysis = assertIs<DocumentAnalysisNotification>(result.envelope?.payload, path.fileName.toString())
        assertNotNull(analysis.compiler.phase, "Missing compiler outcome for $uri")
    }

    private fun prepare(session: LanguageServerSession, origin: Path): CallHierarchyItem {
        val name = span(origin, "target")
        val id = TextDocumentIdentifier(origin.toUri().toString())
        val items = session.server.textDocumentService.prepareCallHierarchy(CallHierarchyPrepareParams(id, name.start))
            .await()
        assertEquals(listOf("target"), items.map { it.name })
        return items.single().also {
            assertEquals(id.uri, it.uri)
            assertEquals(name, it.selectionRange)
            assertEquals(span(origin, "def target() {}"), it.range)
        }
    }

    private fun assertRoundTrip(session: LanguageServerSession, origin: Path) {
        val target = prepare(session, origin)
        val editor = session.server.textDocumentService
        val incoming = editor.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(target)).await()
        assertEquals(listOf("caller"), incoming.map { it.from.name })
        val from = incoming.single()
        assertEquals(target.uri, from.from.uri)
        assertEquals(span(origin, "def caller() { target() }"), from.from.range)
        assertEquals(span(origin, "caller"), from.from.selectionRange)
        val callStart = from.from.selectionRange.end.character
        assertEquals(listOf(span(origin, "target", callStart)), from.fromRanges)

        val outgoing = editor.callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(from.from)).await()
        assertEquals(listOf("target"), outgoing.map { it.to.name })
        val to = outgoing.single()
        assertEquals(target.uri, to.to.uri)
        assertEquals(target.range, to.to.range)
        assertEquals(target.selectionRange, to.to.selectionRange)
        assertEquals(listOf(span(origin, "target()", callStart)), to.fromRanges)
    }

    private fun span(origin: Path, token: String, start: Int = 0): Range {
        val lines = Files.readAllLines(origin)
        val number = lines.indexOfFirst { "class EditorUnicodeCalls" in it }
        require(number >= 0)
        val column = lines[number].indexOf(token, start)
        require(column >= 0)
        return Range(Position(number, column), Position(number, column + token.length))
    }

    private fun <T> CompletableFuture<T>.await(): T = get(TIMEOUT_MS, TimeUnit.MILLISECONDS)

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
