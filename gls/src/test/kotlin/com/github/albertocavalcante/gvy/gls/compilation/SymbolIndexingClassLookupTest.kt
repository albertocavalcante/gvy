package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.worker.WorkerSessionManager
import com.github.albertocavalcante.nativeapi.ParseRequest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        every { workspace.getDependencyClasspath() } returns emptyList()
        every { workspace.getSourceRoots() } returns emptyList()
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

    @Test
    fun `a changed file replaces its old class names`(): Unit = runBlocking {
        val uri = write("Greeter.groovy", "class Greeter {}")
        service.indexFile(uri)

        tempDir.resolve("Greeter.groovy").writeText("class Welcomer {}")
        service.indexFile(uri)

        assertTrue(service.findClasses(ClassNameQuery.Exact("Greeter")).isEmpty(), "Stale class must disappear")
        assertTrue(service.findClasses(ClassNameQuery.Prefix("Gr")).isEmpty())
        assertEquals(listOf("Welcomer"), fqns(service.findClasses(ClassNameQuery.Exact("Welcomer"))))
    }

    @Test
    fun `deleted invalidated and cleared files leave no class names`(): Unit = runBlocking {
        val deleted = write("Deleted.groovy", "class Deleted {}")
        val invalidated = write("Invalidated.groovy", "class Invalidated {}")
        val cleared = write("Cleared.groovy", "class Cleared {}")
        service.indexAllWorkspaceSources(listOf(deleted, invalidated, cleared))

        Files.delete(tempDir.resolve("Deleted.groovy"))
        service.indexFile(deleted)
        service.invalidate(invalidated)

        assertTrue(service.findClasses(ClassNameQuery.Exact("Deleted")).isEmpty())
        assertTrue(service.findClasses(ClassNameQuery.Exact("Invalidated")).isEmpty())
        assertEquals(listOf("Cleared"), fqns(service.findClasses(ClassNameQuery.Prefix(""))))

        service.clear()
        assertTrue(service.findClasses(ClassNameQuery.Prefix("")).isEmpty())
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

    private fun write(name: String, content: String): URI = tempDir.resolve(name).also { it.writeText(content) }.toUri()

    private fun fqns(classes: List<com.github.albertocavalcante.groovyparser.ast.symbols.Symbol.Class>) =
        classes.map { it.fullyQualifiedName }.sorted()
}
