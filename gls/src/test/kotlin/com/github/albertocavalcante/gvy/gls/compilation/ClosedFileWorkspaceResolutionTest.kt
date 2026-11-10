package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.common.ast.isStubSourceName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A closed file reached through navigation is compiled on demand from disk in MINIMAL mode (#1425). It must
 * resolve a class that only the workspace provides: here `Outer2`, declared in `Outers.groovy`, whose file name
 * matches no class in it, so Groovy's by-file-name source lookup cannot find it. The workspace class index
 * supplies it once startup indexing has run, which each test does first.
 */
class ClosedFileWorkspaceResolutionTest {
    @TempDir
    lateinit var tempDir: Path

    private val compilation = GroovyCompilationService(ioDispatcher = Dispatchers.Unconfined)
    private val src: Path by lazy { Files.createDirectories(tempDir.resolve("src")) }

    private fun jarOf(type: Class<*>): Path = Path.of(type.protectionDomain.codeSource.location.toURI())

    private fun write(relative: String, text: String): Path = src.resolve(relative).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, text)
    }

    private val outers =
        "import spock.lang.*\n" +
            "class Outer1 { static abstract class Inner extends Specification {} }\n" +
            "class Outer2 { static abstract class Inner extends Specification {} }\n"

    /** Builds the model, indexes every source except [closed] as startup indexing does, and compiles [closed]. */
    private suspend fun compileClosed(closed: Path): CompiledDocumentSnapshot {
        compilation.updateWorkspaceModel(
            tempDir,
            listOf(jarOf(spock.lang.Specification::class.java), jarOf(groovy.lang.GroovyObject::class.java)),
            listOf(src),
        )
        val others = compilation.workspaceManager.getWorkspaceSources().filter { it != closed }
        compilation.symbolIndexingService.indexAllWorkspaceSources(others.map { it.toUri() })
        // Never opened: navigation reaches it through the content-less path, which compiles from disk.
        assertNotNull(compilation.ensureCompiled(closed.toUri()), "compiled from disk")
        return assertNotNull(compilation.getCompiledSnapshot(closed.toUri()), "published")
    }

    private fun CompiledDocumentSnapshot.messages() = parseResult.diagnostics.map { it.message }

    /** The real files compiled in the unit, by file name: the requested one and what was added for it. */
    private fun CompiledDocumentSnapshot.compiledFiles(): List<String> = parseResult.compilationUnit.iterator()
        .asSequence().map { it.name }.filterNot(::isStubSourceName).map { it.substringAfterLast('/') }.sorted()
        .toList()

    @Test
    fun `a closed file compiled on demand resolves a class from a differently named source`(): Unit = runBlocking {
        write("Outers.groovy", outers)
        val spec = write("TableSpec.groovy", "class TableSpec extends Outer2.Inner {}\n")

        val snapshot = compileClosed(spec)

        val messages = snapshot.messages()
        assertTrue(messages.none { it.contains("unable to resolve class Outer2.Inner") }, "$messages")
    }

    @Test
    fun `only the source declaring the exact qualified name is added`(): Unit = runBlocking {
        write("a/Outers.groovy", "package a\n$outers")
        write(
            "b/Others.groovy",
            "package b\nimport spock.lang.*\nclass Outer2 { static abstract class Inner extends Specification {} }\n",
        )
        val spec = write(
            "app/TableSpec.groovy",
            "package app\nimport a.Outer2\nclass TableSpec extends Outer2.Inner {}\n",
        )

        val snapshot = compileClosed(spec)

        assertTrue(snapshot.messages().none { it.contains("unable to resolve class") }, "${snapshot.messages()}")
        assertEquals(listOf("Outers.groovy", "TableSpec.groovy"), snapshot.compiledFiles(), "b.Outer2 is never added")
    }

    @Test
    fun `a broken declaring source leaves the requested file compiled with the class unresolved`(): Unit =
        runBlocking {
            write("Outers.groovy", "class Outer2 { static abstract class Inner { }\n") // unbalanced: fails to parse
            val spec = write("TableSpec.groovy", "class TableSpec extends Outer2.Inner {\n  def answer() { 42 }\n}\n")

            val snapshot = compileClosed(spec)

            assertNotNull(snapshot.parseResult.ast, "the requested file keeps its AST: ${snapshot.messages()}")
            assertTrue(snapshot.messages().any { it.contains("Outer2.Inner") }, "${snapshot.messages()}")
            assertTrue(snapshot.messages().none { it.contains("Unexpected input") }, "no error from Outers.groovy")
        }

    @Test
    fun `the on-demand compile adds only the sources it resolves, however large the workspace`(): Unit = runBlocking {
        // The deterministic cost bound: MINIMAL compiled 1 file; a WORKSPACE compile would compile all 202.
        repeat(200) { write("filler/Filler$it.groovy", "package filler\nclass Filler$it {}\n") }
        write("Outers.groovy", outers)
        val spec = write("TableSpec.groovy", "class TableSpec extends Outer2.Inner {}\n")
        val started = System.nanoTime()

        val snapshot = compileClosed(spec)

        val millis = (System.nanoTime() - started) / 1_000_000
        println("indexing plus the on-demand compile with 202 sources took $millis ms (informational)")
        assertEquals(listOf("Outers.groovy", "TableSpec.groovy"), snapshot.compiledFiles())
    }
}
