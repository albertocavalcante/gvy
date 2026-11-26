// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.types.Position as GroovyPosition
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.documentation.Documentation
import com.github.albertocavalcante.gvy.gls.project.JenkinsProjectStrategy
import com.github.albertocavalcante.gvy.gls.project.JenkinsQuerySnapshot
import com.github.albertocavalcante.gvy.gls.providers.hover.HoverRequestHandler
import com.github.albertocavalcante.gvy.gls.services.ClasspathService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyGdkProvider
import com.github.albertocavalcante.gvy.gls.services.JvmClasspathReflection
import com.github.albertocavalcante.gvy.gls.services.heldGdkMethods
import com.github.albertocavalcante.gvy.gls.sources.SourceJarExtractor
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigationService
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigator
import com.github.albertocavalcante.gvy.gls.sources.documentationForSelectedBinary
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeHoverInputsTest {
    @Test
    fun `native hover resolves a binary method through the loader that produced the AST`(@TempDir root: Path): Unit =
        runBlocking {
            fun api(side: String, type: String): Path {
                val dir = Files.createDirectory(root.resolve(side))
                val code = "package p; public class Api { public $type work(){return null;} }"
                val java = Files.writeString(dir.resolve("Api.java"), code)
                val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
                assertEquals(0, compiler.run(null, null, null, "-d", dir.toString(), java.toString()))
                return dir
            }
            val selected = api("selected", "String")
            val other = api("other", "Integer")
            val text = "class Client { def call(p.Api api) { api.work() } }"
            val uri = root.resolve("Client.groovy").toUri()
            val docs = DocumentProvider().apply { put(uri, text) }
            val manager = WorkspaceManager().apply { setTestClasspath(root) { listOf(selected) } }
            val service = GroovyCompilationService(documentProvider = docs, workspaceManager = manager)
            try {
                service.classpathService.updateClasspath(listOf(other))
                val held = assertIs<SnapshotAcquisition.Ready>(service.acquireEditorSnapshot(uri)).snapshot
                val loader = held.parseResult.compilationUnit.classLoader
                assertEquals(String::class.java, loader.loadClass("p.Api").getMethod("work").returnType)
                val wrong = assertNotNull(service.classpathService.loadClass("p.Api"))
                assertEquals(Int::class.javaObjectType, wrong.getMethod("work").returnType)
                val params = HoverParams(TextDocumentIdentifier(uri.toString()), Position(0, text.indexOf("work")))
                val hover = HoverRequestHandler(service, docs).getHover(params)
                assertContains(hover.contents.right.value, "String work()")
                assertFalse("Integer work()" in hover.contents.right.value)
            } finally {
                service.shutdown()
            }
        }

    @Test
    fun `native traversal failure is observable at the hover boundary`(): Unit = runBlocking {
        val uri = URI.create("file:///Failure.groovy")
        val docs = DocumentProvider().apply { put(uri, "class Failure {}") }
        var traversed = false
        val service = GroovyCompilationService(documentProvider = docs, workerSessionDecorator = { native ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult {
                    val parsed = native.parse(request)
                    return parsed.copy(astModel = object : GroovyAstModel by parsed.astModel {
                        override fun getNodeAt(uri: URI, position: GroovyPosition): ASTNode? {
                            traversed = true
                            error("failed inside native hover traversal")
                        }
                    })
                }
            }
        })
        try {
            val params = HoverParams(TextDocumentIdentifier(uri.toString()), Position(0, 8))
            val error = assertFailsWith<ResponseErrorException> { HoverRequestHandler(service, docs).getHover(params) }
            assertTrue(traversed)
            assertEquals(ResponseErrorCode.InternalError.value, error.responseError.code)
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `replacing the manager inside the same Jenkins capability retires the captured facts`(@TempDir root: Path): Unit =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val strategy = JenkinsProjectStrategy(scope)
            val manager = spyk(WorkspaceManager())
            every { manager.getJenkinsCapabilities() } returns strategy
            val service = GroovyCompilationService(workspaceManager = manager)
            try {
                val config = ServerConfiguration()
                strategy.initialize(root, config)?.join()
                val request = JenkinsQuerySnapshot.capture(service, root.resolve("Jenkinsfile").toUri())
                val facts = request.materialize()
                assertTrue(facts.isJenkinsFile)
                assertTrue(facts.isCurrent())
                strategy.initialize(root, config)?.join()
                assertFalse(facts.isCurrent())
            } finally {
                service.shutdown()
                scope.cancel()
            }
        }

    @Test
    fun `external docs come from the source archive paired with the held binary`(@TempDir root: Path): Unit =
        runBlocking {
            fun jar(path: Path, name: String, bytes: ByteArray) {
                JarOutputStream(Files.newOutputStream(path)).use { out ->
                    out.putNextEntry(JarEntry(name))
                    out.write(bytes)
                }
            }
            val compiled = Files.createDirectory(root.resolve("classes"))
            val source = Files.writeString(root.resolve("Api.java"), "package p; public class Api {}")
            val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
            assertEquals(0, compiler.run(null, null, null, "-d", compiled.toString(), source.toString()))
            val bytes = Files.readAllBytes(compiled.resolve("p/Api.class"))
            val extractor = SourceJarExtractor(root.resolve("extracted"))
            val navigator = SourceNavigationService(sourceExtractor = extractor)
            val binaries = listOf("first", "second").associateWith { side ->
                val location = Files.createDirectory(root.resolve(side))
                val binary = location.resolve("library.jar")
                val sources = location.resolve("library-sources.jar")
                jar(binary, "p/Api.class", bytes)
                val doc = "package p; /** $side artifact documentation. */ public class Api {}"
                jar(sources, "p/Api.java", doc.toByteArray())
                // Deliberately seed the legacy name cache with the last FQN.
                extractor.extractAndIndex(sources)
                binary
            }
            val custom = mockk<SourceNavigator>()
            for ((side, binary) in binaries) {
                URLClassLoader(arrayOf(binary.toUri().toURL()), null).use { loader ->
                    val type = loader.loadClass("p.Api")
                    val doc = assertNotNull(navigator.documentationFor(type))
                    assertContains(doc.summary + doc.description, "$side artifact documentation")

                    val resource = assertNotNull(loader.getResource("p/Api.class")).toURI()
                    val attachment = Documentation(summary = "$side custom documentation")
                    coEvery { custom.navigateToSource(resource, type.name) } returns
                        SourceNavigator.SourceResult.SourceLocation(binary.toUri(), type.name, documentation = attachment)
                    assertEquals(attachment, custom.documentationForSelectedBinary(type))
                }
            }

            // Child-first class loading paired with parent-first resource lookup exposes the other jar's class bytes.
            val first = binaries.getValue("first").toUri().toURL()
            val second = binaries.getValue("second").toUri().toURL()
            URLClassLoader(arrayOf(first), null).use { resources ->
                val classes = object : URLClassLoader(arrayOf(second), null) {
                    override fun getResource(name: String) = resources.getResource(name)
                }
                classes.use {
                    val type = it.loadClass("p.Api")
                    assertEquals(second, type.protectionDomain.codeSource.location)
                    assertEquals(resources.getResource("p/Api.class"), type.getResource("/p/Api.class"))
                    assertEquals(null, custom.documentationForSelectedBinary(type))
                }
            }
        }

    @Test
    fun `held GDK lookup preserves receiver precedence for overlapping extension signatures`() {
        val loader = javaClass.classLoader
        val reflection = JvmClasspathReflection { loader }
        val classpath = mockk<ClasspathService>()
        every { classpath.loadClass(any()) } answers { reflection.loadClass(firstArg()) }
        val baseline = GroovyGdkProvider(classpath).getQualifiedMethodsForType("java.lang.String")
        for (name in listOf("getAt", "reverse", "take")) {
            val expected = baseline.filter { it.display.name == name }
            assertTrue(expected.isNotEmpty(), "Expected real GDK extensions for $name")
            assertEquals(expected, heldGdkMethods(reflection, "java.lang.String", name))
        }
    }

    @Test
    fun `source-only hovers do not capture reference graphs or enumerate shadows`() {
        val service = mockk<GroovyCompilationService>()
        val lookup = HoverMemberLookup.forRequest(service, URI.create("file:///Local.groovy"), mockk())
        assertEquals(0L, lookup.version)
        assertTrue(lookup.isCurrent())
        verify(exactly = 0) { service.referenceReadiness(any()) }
    }

    @Test
    fun `a held local member miss never borrows a global index publication`() {
        val service = mockk<GroovyCompilationService>()
        every { service.referenceReadiness(any()) } throws AssertionError("local miss consulted global index")
        val local = org.codehaus.groovy.ast.ClassNode("Local", 0, org.codehaus.groovy.ast.ClassHelper.OBJECT_TYPE)
        val module = mockk<org.codehaus.groovy.ast.ModuleNode> { every { classes } returns listOf(local) }
        val parsed = mockk<ParseResult> { every { ast } returns module }
        val held = mockk<CompiledDocumentSnapshot> { every { parseResult } returns parsed }
        val lookup = HoverMemberLookup.forRequest(service, URI.create("file:///Local.groovy"), held)
        assertEquals(null, lookup.findField("Local", "missing"))
        assertEquals(emptyList(), lookup.findMethods("Local", "missing"))
        assertTrue(lookup.isCurrent())
        verify(exactly = 0) { service.referenceReadiness(any()) }
        assertFailsWith<AssertionError> { lookup.findField("Foreign", "missing") }
        verify(exactly = 1) { service.referenceReadiness(any()) }
    }

    @Test
    fun `Jenkins tokens retain vars names without requesting bodies or documentation`(@TempDir root: Path): Unit =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val strategy = spyk(JenkinsProjectStrategy(scope))
            val manager = spyk(WorkspaceManager())
            every { manager.getJenkinsCapabilities() } returns strategy
            val service = GroovyCompilationService(workspaceManager = manager)
            try {
                val vars = Files.createDirectory(root.resolve("vars"))
                Files.writeString(vars.resolve("hello.groovy"), "def call() {}")
                Files.writeString(vars.resolve("hello.txt"), "shared step docs")
                strategy.initialize(root, ServerConfiguration())?.join()
                val expected = strategy.getGlobalVariables().map { it.name }.toSet()
                assertEquals(setOf("hello"), expected)
                every { strategy.getGlobalVariables() } throws AssertionError("tokens read vars bodies")
                val facts = JenkinsQuerySnapshot.capture(service, root.resolve("Jenkinsfile").toUri()).materialize()
                assertEquals(expected, facts.variables)
                assertTrue(facts.isCurrent())
                Files.writeString(vars.resolve("another.groovy"), "")
                assertFalse(facts.isCurrent())
            } finally {
                service.shutdown()
                scope.cancel()
            }
        }

    @Test
    fun `member lookups validate the producer of the consulted document`(): Unit = runBlocking {
        val uri = URI.create("file:///Client.groovy")
        val peer = URI.create("file:///Peer.groovy")
        val source = "class Client { def use(Peer peer) { peer.work() } }"
        val other = "class Peer { String work() { 'yes' } }"
        val docs = DocumentProvider().apply {
            put(uri, source)
            put(peer, other)
        }
        val service = GroovyCompilationService(documentProvider = docs)
        try {
            service.compile(peer, other)
            val held = assertIs<SnapshotAcquisition.Ready>(service.acquireEditorSnapshot(uri)).snapshot
            val members = HoverMemberLookup.forRequest(service, uri, held)
            val caller = assertNotNull(held.parseResult.ast).classes.single()
            val receiver = caller.getMethods("use").single().parameters.single().type.redirect()
            val bound = assertNotNull(members.forReceiver(receiver))
            assertEquals("work", assertNotNull(bound.findMethod("Peer", "work", 0)).name)
            assertTrue(members.isCurrent())
            val index = assertNotNull(service.getWorkspaceSymbolIndex())
            val original = assertNotNull(index.getDocument(peer))
            index.updateDocument(peer, original.copy(), other)
            assertFalse(members.isCurrent())
            val replaced = HoverMemberLookup.forRequest(service, uri, held)
            val rebound = assertNotNull(replaced.forReceiver(receiver))
            val failure = assertFailsWith<ResponseErrorException> { rebound.findMethod("Peer", "work", 0) }
            assertEquals(ResponseErrorCode.RequestFailed.value, failure.responseError.code)
        } finally {
            service.shutdown()
        }
    }
}
