// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls

import com.github.albertocavalcante.gvy.gls.execution.ExecutionOptions
import com.github.albertocavalcante.gvy.gls.execution.PrepareFileExecutionParams
import groovy.lang.GroovySystem
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.CodeLensParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class FileExecutionEndpointTest {
    @TempDir lateinit var workspace: Path

    @Test
    fun `root scratch skips broken Gradle model and uses containing workspace`() {
        val first = Files.createDirectory(workspace.resolve("first"))
        val second = Files.createDirectory(workspace.resolve("second"))
        Files.writeString(second.resolve("settings.gradle"), "throw new RuntimeException('must not evaluate')")
        val source = second.resolve("hello.groovy")
        val content = "println 'hello'"
        Files.writeString(source, content)
        val server = GroovyLanguageServer()
        server.connect(SynchronizingTestLanguageClient())
        try {
            server.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(
                        WorkspaceFolder(first.toUri().toString(), "first"),
                        WorkspaceFolder(second.toUri().toString(), "second"),
                    )
                    capabilities = ClientCapabilities()
                },
            ).get(30, TimeUnit.SECONDS)
            server.initialized(InitializedParams())
            val uri = source.toUri().toString()
            server.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, content)))
            val runtime = Path.of(GroovySystem::class.java.protectionDomain.codeSource.location.toURI()).toString()
            val request = PrepareFileExecutionParams(
                uri = uri,
                version = 1,
                action = "run",
                trusted = true,
                options = ExecutionOptions(
                    bundledRuntime = listOf(runtime),
                    outputDirectory = workspace.resolve("out").toString(),
                ),
            )
            val plan = server.prepareFileExecution(request).get(30, TimeUnit.SECONDS)
            assertEquals("ready", plan.kind)
            assertEquals(second.toRealPath().toString(), plan.launch?.workingDirectory)
            assertHostedAndUnknownContexts(server, second, request, content)
            assertNestedBuildBoundary(server, second, request, content)
            assertProjectScopeAndOutsideFile(server, second, request, content)
        } finally {
            server.shutdown().get(30, TimeUnit.SECONDS)
        }
    }

    private fun assertHostedAndUnknownContexts(
        server: GroovyLanguageServer,
        root: Path,
        request: PrepareFileExecutionParams,
        content: String,
    ) {
        val module = Files.createDirectory(root.resolve("app"))
        val buildUri = openSource(server, module.resolve("build.gradle"), content)
        val hosted = server.prepareFileExecution(request.copy(uri = buildUri)).get(30, TimeUnit.SECONDS)
        assertEquals("unavailable", hosted.kind)
        assertEquals("groovy.gradle.selectTask", hosted.alternativeCommand)
        val tools = Files.createDirectory(root.resolve("tools"))
        val customUri = openSource(server, tools.resolve("Main.groovy"), content)
        val choice = server.prepareFileExecution(request.copy(uri = customUri)).get(30, TimeUnit.SECONDS)
        assertEquals("choices", choice.kind)
        assertEquals(listOf("standalone", "inspect-project"), choice.choices?.map { it.id })
        val inspect = server.prepareFileExecution(
            request.copy(uri = customUri, contextId = "inspect-project"),
        ).get(30, TimeUnit.SECONDS)
        assertEquals("save", inspect.kind)
        assertEquals(root.toRealPath().toString(), inspect.scopeDirectory)
    }

    private fun assertNestedBuildBoundary(
        server: GroovyLanguageServer,
        root: Path,
        request: PrepareFileExecutionParams,
        content: String,
    ) {
        val nested = Files.createDirectory(root.resolve("included"))
        Files.writeString(nested.resolve("settings.gradle"), "")
        val source = Files.createDirectories(nested.resolve("src/main/groovy")).resolve("Main.groovy")
        val nestedRequest = request.copy(uri = openSource(server, source, content))
        val boundary = server.prepareFileExecution(nestedRequest).get(30, TimeUnit.SECONDS)
        assertEquals("unavailable", boundary.kind)
        assertTrue(boundary.reason!!.contains("nested"))
        val standalone = server.prepareFileExecution(
            nestedRequest.copy(contextId = "standalone"),
        ).get(30, TimeUnit.SECONDS)
        assertEquals("ready", standalone.kind)
    }

    private fun assertProjectScopeAndOutsideFile(
        server: GroovyLanguageServer,
        root: Path,
        request: PrepareFileExecutionParams,
        content: String,
    ) {
        val projectRequest = request.copy(forceProject = true)
        val save = server.prepareFileExecution(projectRequest).get(30, TimeUnit.SECONDS)
        assertEquals("save", save.kind)
        assertEquals(root.toRealPath().toString(), save.scopeDirectory)
        val alteredHome = projectRequest.copy(
            savedScopeDirectory = root.toRealPath().toString(),
            options = request.options.copy(environment = mapOf("GRADLE_USER_HOME" to "/different")),
        )
        val rejected = server.prepareFileExecution(alteredHome).get(30, TimeUnit.SECONDS)
        assertEquals("unavailable", rejected.kind)
        assertTrue(rejected.reason!!.contains("GRADLE_USER_HOME"))
        val outsideUri = openSource(server, workspace.resolve("outside.groovy"), content)
        val outsidePlan = server.prepareFileExecution(
            projectRequest.copy(uri = outsideUri),
        ).get(30, TimeUnit.SECONDS)
        assertEquals("unavailable", outsidePlan.kind)
    }

    private fun openSource(server: GroovyLanguageServer, source: Path, content: String): String {
        Files.writeString(source, content)
        val uri = source.toUri().toString()
        server.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, content)))
        return uri
    }

    @Test
    fun `endpoint checks trust saved content and current document version`() {
        val source = workspace.resolve("hello.groovy")
        val content = "println 'hello'"
        Files.writeString(source, content)
        val server = GroovyLanguageServer()
        server.connect(SynchronizingTestLanguageClient())
        try {
            server.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder(workspace.toUri().toString(), "workspace"))
                    capabilities = ClientCapabilities()
                },
            ).get(30, TimeUnit.SECONDS)
            server.initialized(InitializedParams())
            val uri = source.toUri().toString()
            server.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, content)))
            val runtime = Path.of(GroovySystem::class.java.protectionDomain.codeSource.location.toURI()).toString()
            val request = PrepareFileExecutionParams(
                uri = uri,
                version = 1,
                action = "run",
                trusted = true,
                options = ExecutionOptions(
                    bundledRuntime = listOf(runtime),
                    outputDirectory = workspace.resolve("out").toString(),
                ),
            )
            assertEquals("unavailable", server.prepareFileExecution(request.copy(trusted = false)).get().kind)
            assertEquals("unavailable", server.prepareFileExecution(request.copy(version = 2)).get().kind)
            assertEquals("ready", server.prepareFileExecution(request).get().kind)
            val lenses = server.textDocumentService.codeLens(CodeLensParams(TextDocumentIdentifier(uri)))
                .get(30, TimeUnit.SECONDS)
            assertTrue(lenses.any { it.command?.command == "groovy.runFile" })
            Files.writeString(source, "println 'changed'")
            assertEquals("unavailable", server.prepareFileExecution(request).get().kind)
        } finally {
            server.shutdown().get(30, TimeUnit.SECONDS)
        }
    }
}
