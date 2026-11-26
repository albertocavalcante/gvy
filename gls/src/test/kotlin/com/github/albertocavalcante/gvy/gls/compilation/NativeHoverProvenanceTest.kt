// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.documentation.Documentation
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverRequestHandler
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.sources.SourceJarExtractor
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigationService
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.semantics.SemanticType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Semaphore
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeHoverProvenanceTest {
    @Test
    fun `foreign source import never borrows docs from its binary namesake`(@TempDir root: Path): Unit = runBlocking {
        val compiled = Files.createDirectory(root.resolve("classes"))
        val java = Files.writeString(root.resolve("Api.java"), "package p; public class Api {}")
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "-d", compiled.toString(), java.toString()))
        val binary = root.resolve("library.jar")
        jar(binary, "p/Api.class", Files.readAllBytes(compiled.resolve("p/Api.class")))
        val doc = "package p; /** Binary-only artifact prose. */ public class Api {}"
        jar(root.resolve("library-sources.jar"), "p/Api.java", doc.toByteArray())
        val navigator = SourceNavigationService(sourceExtractor = SourceJarExtractor(root.resolve("docs")))
        val source = "import p.Api\nclass Client { Api field }"
        val caller = root.resolve("Client.groovy").toUri()
        val peer = root.resolve("Peer.groovy").toUri()
        val documents = DocumentProvider().apply {
            put(caller, source)
            put(peer, "package p; class Api {}")
        }
        val manager = WorkspaceManager().apply { setTestClasspath(root) { listOf(binary) } }
        val service = GroovyCompilationService(
            documentProvider = documents,
            workspaceManager = manager,
            sourceNavigator = navigator
        )
        try {
            val parsed = assertIs<SnapshotAcquisition.Ready>(service.acquireEditorSnapshot(caller)).snapshot.parseResult
            val imported = parsed.ast!!.imports.single().type.redirect()
            assertTrue(imported.isPrimaryClassNode)
            assertEquals(Path.of(peer).toString(), imported.module.context.name)
            val namesake = parsed.compilationUnit.classLoader.loadClass("p.Api", false, true, false)
            assertContains(assertNotNull(navigator.documentationFor(namesake)).summary, "Binary-only artifact prose")
            val answer = HoverRequestHandler(service, documents).getHover(params(caller, 9)).contents.right.value
            assertContains(answer, "p.Api")
            assertFalse("Binary-only artifact prose" in answer, answer)
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `custom navigator receives the selected JDK class resource`(@TempDir root: Path): Unit = runBlocking {
        val caller = root.resolve("Client.groovy").toUri()
        val documents = DocumentProvider().apply { put(caller, "import java.util.ArrayList\nclass Client {}") }
        val navigator = mockk<SourceNavigator>()
        val type = java.util.ArrayList::class.java
        val origin = assertNotNull(type.getResource("/java/util/ArrayList.class")).toURI()
        coEvery { navigator.navigateToSource(origin, type.name) } returns SourceNavigator.SourceResult.SourceLocation(
            root.resolve("ArrayList.java").toUri(),
            type.name,
            documentation = Documentation(summary = "Custom JDK prose"),
        )
        val service = GroovyCompilationService(documentProvider = documents, sourceNavigator = navigator)
        try {
            val answer = HoverRequestHandler(service, documents).getHover(params(caller, 19)).contents.right.value
            assertContains(answer, "Custom JDK prose")
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `request-time missing receiver lookup never probes Groovy sources`(@TempDir root: Path): Unit = runBlocking {
        val caller = root.resolve("Client.groovy").toUri()
        val source = "class Probe {}\nclass Client { def use(Probe p) { p.missingMember() } }"
        val documents = DocumentProvider().apply { put(caller, source) }
        val service = GroovyCompilationService(documentProvider = documents)
        try {
            val parsed = assertIs<SnapshotAcquisition.Ready>(service.acquireEditorSnapshot(caller)).snapshot.parseResult
            val loader = parsed.compilationUnit.classLoader
            val probes = mutableListOf<String>()
            val previous = loader.resourceLoader
            try {
                loader.resourceLoader = groovy.lang.GroovyResourceLoader { name ->
                    probes.add(name)
                    null
                }
                val answer = HoverRequestHandler(service, documents)
                    .getHover(params(caller, source.lines()[1].indexOf("missingMember") + 1, 1)).contents.right.value
                assertContains(answer, "missingMember")
                assertEquals(emptyList(), probes, "Held source lookup after compilation")
            } finally {
                loader.resourceLoader = previous
            }
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `inference on a held binary receiver ignores an indexed source namesake`(@TempDir root: Path): Unit =
        runBlocking {
            val workspace = Files.createDirectory(root.resolve("workspace"))
            val src = Files.createDirectory(workspace.resolve("src"))
            val peerText = "package java.util.concurrent; class Semaphore { String drainPermits() { 'wrong' } }"
            val peer = Files.writeString(src.resolve("Semaphore.groovy"), peerText).toUri()
            val type = Semaphore::class.java
            val source = "class Client { def use(java.util.concurrent.Semaphore permits) { " +
                "def result = permits.drainPermits(); result } }"
            val caller = Files.writeString(root.resolve("Client.groovy"), source).toUri()
            val documents = DocumentProvider()
            val service = GroovyCompilationService(documentProvider = documents)
            try {
                service.workspaceManager.initializeWorkspace(workspace)
                assertEquals(listOf(Path.of(peer)), service.workspaceManager.getWorkspaceSources())
                service.getWorkspaceCompiler().compileWorkspace()
                val stored = assertNotNull(service.getWorkspaceSymbolIndex()?.getDocument(peer))
                assertEquals(
                    SemanticType.Known("java.lang.String"),
                    stored.symbols.single { it.name == "drainPermits" }.type
                )
                val held = assertIs<SnapshotAcquisition.Ready>(service.acquireEditorSnapshot(caller)).snapshot
                val receiver = held.parseResult.ast!!.classes.single().getMethods(
                    "use"
                ).single().parameters.single().type.redirect()
                assertFalse(receiver.isPrimaryClassNode)
                assertTrue(receiver.isResolved)
                assertEquals(type.name, receiver.name)
                assertEquals(Int::class.javaPrimitiveType, receiver.typeClass.getMethod("drainPermits").returnType)
                val answer = HoverRequestHandler(service, documents)
                    .getHover(params(caller, source.indexOf("result") + 1)).contents.right.value
                assertContains(answer, "Variable Declaration")
                assertFalse("String result" in answer, answer)
            } finally {
                service.shutdown()
            }
        }

    private fun params(uri: URI, column: Int, line: Int = 0) =
        HoverParams(TextDocumentIdentifier(uri.toString()), Position(line, column))

    private fun jar(path: Path, name: String, bytes: ByteArray) {
        JarOutputStream(Files.newOutputStream(path)).use {
            it.putNextEntry(JarEntry(name))
            it.write(bytes)
        }
    }
}
