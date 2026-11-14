// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.ast.symbols.buildFromVisitor
import com.github.albertocavalcante.gvy.gls.worker.WorkerSessionManager
import com.github.albertocavalcante.nativeapi.ParseRequest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Class lookups by simple name or prefix track every way an entry is added, replaced or dropped (#861). */
class SymbolIndexingClassLookupTest {
    @TempDir
    lateinit var tempDir: Path

    private val parser = GroovyParserFacade()
    private val ownership = ArtifactOwnership()
    private lateinit var service: SymbolIndexingService

    @BeforeTest
    fun setUp() {
        val workers = mockk<WorkerSessionManager>()
        coEvery { workers.parse(any<ParseRequest>()) } answers { parser.parse(firstArg()) }
        val workspace = mockk<WorkspaceManager>(relaxed = true)
        // Disk indexing reads one workspace model (#1278)
        every { workspace.current() } returns
            WorkspaceModel(null, emptyList(), emptyList(), emptyList()) { _, _ -> "fp" }
        service = SymbolIndexingService(Dispatchers.Unconfined, workers, workspace, ownership)
    }

    @Test
    fun `an unopened workspace class is found by exact simple name`(): Unit = runBlocking {
        val uri = write("Greeter.groovy", "package app\nclass Greeter {}\nclass GreeterHelper {}")

        service.indexAllWorkspaceSources(listOf(uri))

        assertEquals(listOf("app.Greeter"), fqns(service.findClasses(ClassNameQuery.Exact("Greeter"))))
        assertTrue(service.findClasses(ClassNameQuery.Exact("greeter")).isEmpty(), "Exact lookup is case-sensitive")
    }

    @Test
    fun `prefix lookup ignores case and spans files`(): Unit = runBlocking {
        val first = write("A.groovy", "package a\nclass Greeter {}")
        val second = write("B.groovy", "package b\nclass GreetingCard {}\nclass Other {}")

        service.indexAllWorkspaceSources(listOf(first, second))

        assertEquals(listOf("a.Greeter", "b.GreetingCard"), fqns(service.findClasses(ClassNameQuery.Prefix("gre"))))
        assertEquals(
            listOf("a.Greeter", "b.GreetingCard", "b.Other"),
            fqns(service.findClasses(ClassNameQuery.Prefix(""))),
        )
    }

    // Results re-check each file's current entry, so only the recorded keys prove a removal hook ran.

    @Test
    fun `a changed file replaces its old class names`(): Unit = runBlocking {
        val uri = write("Greeter.groovy", "class Greeter {}")
        service.indexFile(uri)

        tempDir.resolve("Greeter.groovy").writeText("class Welcomer {}")
        service.indexFile(uri)

        assertTrue(service.findClasses(ClassNameQuery.Exact("Greeter")).isEmpty(), "Stale class must disappear")
        assertEquals(listOf("Welcomer"), fqns(service.findClasses(ClassNameQuery.Exact("Welcomer"))))
        assertEquals(setOf("welcomer"), service.classNameKeys)
    }

    @Test
    fun `a deleted file leaves no class names`(): Unit = runBlocking {
        val uri = write("Deleted.groovy", "class Deleted {}")
        service.indexFile(uri)
        assertEquals(setOf("deleted"), service.classNameKeys, "setup must index the class")

        Files.delete(tempDir.resolve("Deleted.groovy"))
        service.indexFile(uri)

        assertTrue(service.findClasses(ClassNameQuery.Exact("Deleted")).isEmpty())
        assertEquals(emptySet(), service.classNameKeys)
    }

    @Test
    fun `a source retargeted to managed content hides and removes its old index`(): Unit = runBlocking {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "Symlink privileges are not assumed")
        val managed = Files.createDirectory(tempDir.resolve("managed"))
        val target = Files.writeString(managed.resolve("Mode.groovy"), "class Managed {}")
        val source = tempDir.resolve("Mode.groovy")
        Files.writeString(source, "class Original {}")
        val uri = source.toUri()
        val workers = mockk<WorkerSessionManager>()
        coEvery { workers.parse(any<ParseRequest>()) } answers { parser.parse(firstArg()) }
        val workspace = WorkspaceManager(sourceExclusion = { it.startsWith(managed) })
        var open = false
        val guarded = SymbolIndexingService(Dispatchers.Unconfined, workers, workspace, ownership, isOpen = { open })
        guarded.indexFile(uri)
        assertNotNull(guarded.getSymbolIndex(uri))
        assertEquals(setOf("original"), guarded.classNameKeys)

        val stale = ownership.begin(uri)
        ownership.retire(uri)
        Files.delete(source)
        Files.createSymbolicLink(source, target)

        assertNull(guarded.getSymbolIndex(uri), "reads must not expose an alias before the watcher runs")
        assertTrue(guarded.getAllSymbolIndices().isEmpty())
        assertTrue(guarded.findClasses(ClassNameQuery.Exact("Original")).isEmpty())
        guarded.refreshAfterClose(uri, stale)
        assertEquals(setOf("original"), guarded.classNameKeys, "retired disk work cannot remove newer facts")
        open = true
        guarded.refreshAfterClose(uri)
        assertEquals(setOf("original"), guarded.classNameKeys, "disk work cannot erase an open buffer's facts")

        open = false
        guarded.indexFile(uri)
        assertEquals(emptySet(), guarded.classNameKeys)
        assertNull(guarded.getSymbolIndex(uri))
    }

    @Test
    fun `indexing parse carries a fresh implicit source admission check`(): Unit = runBlocking {
        val managed = Files.createDirectory(tempDir.resolve("managed"))
        val managedSource = Files.writeString(managed.resolve("Hidden.groovy"), "class Hidden {}")
        val source = write("Visible.groovy", "class Visible {}")
        val workers = mockk<WorkerSessionManager>()
        val captured = slot<ParseRequest>()
        coEvery { workers.parse(capture(captured)) } answers { parser.parse(firstArg()) }
        val workspace = mockk<WorkspaceManager>(relaxed = true)
        every { workspace.current() } returns
            WorkspaceModel(null, emptyList(), listOf(tempDir), emptyList()) { _, _ -> "fp" }
        every { workspace.isExcludedSource(any<Path>()) } answers { firstArg<Path>().startsWith(managed) }
        val guarded = SymbolIndexingService(Dispatchers.Unconfined, workers, workspace, ownership)

        guarded.indexFile(source)

        val sourcePathAdmission = requireNotNull(captured.captured.sourcePathAdmission)
        assertFalse(sourcePathAdmission(managedSource))
    }

    @Test
    fun `an invalidated file leaves no class names`(): Unit = runBlocking {
        val uri = write("Invalidated.groovy", "class Invalidated {}")
        service.indexFile(uri)
        assertEquals(setOf("invalidated"), service.classNameKeys, "setup must index the class")

        service.invalidate(uri)

        assertEquals(emptySet(), service.classNameKeys)
    }

    @Test
    fun `removing one of two files that share a class name keeps the name`(): Unit = runBlocking {
        val first = write("First.groovy", "package a\nclass Shared {}")
        val second = write("Second.groovy", "package b\nclass Shared {}")
        service.indexAllWorkspaceSources(listOf(first, second))

        service.invalidate(first)

        assertEquals(setOf("shared"), service.classNameKeys)
        assertEquals(listOf("b.Shared"), fqns(service.findClasses(ClassNameQuery.Exact("Shared"))))
    }

    @Test
    fun `clear leaves no class names`(): Unit = runBlocking {
        service.indexAllWorkspaceSources(listOf(write("A.groovy", "class A {}"), write("B.groovy", "class B {}")))
        assertEquals(setOf("a", "b"), service.classNameKeys)

        service.clear()

        assertEquals(emptySet(), service.classNameKeys)
    }

    @Test
    fun `closing an untitled buffer leaves no class names`(): Unit = runBlocking {
        val untitled = URI.create("untitled:Untitled-1")
        val buffer = "class Scratch {}"
        service.ensureIndexed(ownership.begin(untitled), buffer) { parse(untitled, buffer) }
        assertEquals(setOf("scratch"), service.classNameKeys)

        service.refreshAfterClose(untitled)

        assertEquals(emptySet(), service.classNameKeys)
    }

    @Test
    fun `a re-install keeps kept names and shows new names before the entry write`() {
        // Readers do not lock: a name must be findable from the moment its entry can be read.
        val uri = URI.create("file:///Foo.groovy")
        val names = WorkspaceClassNames()
        val before = classesOf(uri, "class Foo {}\nclass Old {}")
        names.replace(uri, before) { null }

        val during = mutableMapOf<String, Set<URI>>()
        names.replace(uri, classesOf(uri, "class Foo {}\nclass Bar {}")) {
            listOf("Foo", "Bar", "Old").forEach { during[it] = names.files(ClassNameQuery.Exact(it)) }
            before
        }

        assertEquals(mapOf("Foo" to setOf(uri), "Bar" to setOf(uri), "Old" to setOf(uri)), during)
        assertEquals(setOf("foo", "bar"), names.keys())
    }

    @Test
    fun `prefix lookup folds case per character like startsWith`(): Unit = runBlocking {
        service.indexFile(write("Info.groovy", "class İnfo {}"))

        assertEquals(listOf("İnfo"), fqns(service.findClasses(ClassNameQuery.Prefix("in"))))
    }

    @Test
    fun `a compiled buffer replaces the disk classes of the same file`(): Unit = runBlocking {
        val uri = write("Greeter.groovy", "class Greeter {}")
        service.indexFile(uri)
        val buffer = "class Buffered {}"
        val model = parser.parse(ParseRequest(uri = uri, content = buffer)).astModel

        service.ensureIndexed(ownership.begin(uri), buffer) { model }

        assertTrue(service.findClasses(ClassNameQuery.Exact("Greeter")).isEmpty())
        assertEquals(listOf("Buffered"), fqns(service.findClasses(ClassNameQuery.Exact("Buffered"))))
    }

    private fun parse(uri: URI, content: String) = parser.parse(ParseRequest(uri = uri, content = content)).astModel

    private fun classesOf(uri: URI, content: String) =
        com.github.albertocavalcante.groovyparser.ast.symbols.SymbolIndex().buildFromVisitor(parse(uri, content))
            .getSymbols(uri).filterIsInstance<com.github.albertocavalcante.groovyparser.ast.symbols.Symbol.Class>()

    private fun write(name: String, content: String): URI = tempDir.resolve(name).also { it.writeText(content) }.toUri()

    private fun fqns(classes: List<com.github.albertocavalcante.groovyparser.ast.symbols.Symbol.Class>) =
        classes.map { it.fullyQualifiedName }.sorted()
}
