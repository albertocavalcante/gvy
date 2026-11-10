package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.common.ast.isStubSourceName
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
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

    private val documents = DocumentProvider()
    private val compilation =
        GroovyCompilationService(ioDispatcher = Dispatchers.Unconfined, documentProvider = documents)
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

    /**
     * Builds the model, opens [buffers] (each compiled, so the index holds their classes), indexes every other
     * source except [closed] as startup indexing does, and compiles [closed] from disk.
     */
    private suspend fun compileClosed(closed: Path, buffers: Map<Path, String> = emptyMap()): CompiledDocumentSnapshot {
        compilation.updateWorkspaceModel(
            tempDir,
            listOf(jarOf(spock.lang.Specification::class.java), jarOf(groovy.lang.GroovyObject::class.java)),
            listOf(src),
        )
        buffers.forEach { (path, text) ->
            documents.put(path.toUri(), text)
            compilation.compile(path.toUri(), text)
        }
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
    fun `a broken declaring source leaves the file compiled with the class unresolved`(): Unit = runBlocking {
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

    @Test
    fun `a classpath class wins over a workspace file declaring the same name`(): Unit = runBlocking {
        // The index is consulted only after Groovy's own lookup misses, so it can never shadow the classpath.
        write("shadow/Fake.groovy", "package spock.lang\nclass Specification {}\n")
        val spec = write("TableSpec.groovy", "class TableSpec extends spock.lang.Specification {}\n")

        val snapshot = compileClosed(spec)

        assertEquals(listOf("TableSpec.groovy"), snapshot.compiledFiles(), "the workspace file is never added")
    }

    @Test
    fun `a file declaring two classes is added once when the second is referenced first`(): Unit = runBlocking {
        // Both names lead to one file: the first answer queues it, and the second is found in the unit.
        write("Pair.groovy", "class First {}\nclass Second {}\n")
        val spec = write("TableSpec.groovy", "class TableSpec {\n  Second second\n  First first\n}\n")

        val snapshot = compileClosed(spec)

        assertTrue(snapshot.messages().none { it.contains("unable to resolve class") }, "${snapshot.messages()}")
        assertEquals(listOf("Pair.groovy", "TableSpec.groovy"), snapshot.compiledFiles())
    }

    @Test
    fun `an open buffer of the declaring file wins over its disk copy`(): Unit = runBlocking {
        val declaring = write("Outers.groovy", "class Outer2 {}\n") // on disk: no Inner yet
        val spec = write("TableSpec.groovy", "class TableSpec extends Outer2.Inner {}\n")

        val buffer = "class Outer2 { static abstract class Inner {} }\n"
        val snapshot = compileClosed(spec, buffers = mapOf(declaring to buffer))

        assertTrue(snapshot.messages().none { it.contains("unable to resolve class") }, "${snapshot.messages()}")
    }

    @Test
    fun `a class declared only in an open buffer is located`(): Unit = runBlocking {
        val declaring = write("Outers.groovy", "class Other {}\n")
        val spec = write("TableSpec.groovy", "class TableSpec extends Outer3.Inner {}\n")

        val buffer = "class Outer3 { static abstract class Inner {} }\n"
        val snapshot = compileClosed(spec, buffers = mapOf(declaring to buffer))

        assertTrue(snapshot.messages().none { it.contains("unable to resolve class") }, "${snapshot.messages()}")
    }

    @Test
    fun `a hierarchy chain is located transitively up to the cap`(): Unit = runBlocking {
        // C0 extends C1 extends ... C39, each in a file named otherwise: every added file resolves through the
        // locator too, so the chain is followed, but only until the per-compile cap of located files.
        repeat(40) { link ->
            val parent = if (link < 39) " extends C${link + 1}" else ""
            write("chain/Link$link.groovy", "class C$link$parent {}\n")
        }
        val spec = write("TableSpec.groovy", "class TableSpec extends C0 {}\n")

        val snapshot = compileClosed(spec)

        assertEquals(1 + 32, snapshot.compiledFiles().size, "${snapshot.compiledFiles()}")
        assertTrue(snapshot.parseResult.outcome.unlocatedClasses.isNotEmpty(), "the cut is recorded")
        // The stand-in for a class past the cap is declared by no module, so nothing indexes it as a declaration.
        val declared = snapshot.parseResult.compilationUnit.ast.modules.flatMap { it.classes }.map { it.name }
        assertTrue(snapshot.parseResult.outcome.unlocatedClasses.none { it in declared }, "$declared")
    }

    @Test
    fun `a name nested two levels deep is located through its top-level class`(): Unit = runBlocking {
        write("Nest.groovy", "class Outer { static class Mid { static abstract class Inner {} } }\n")
        val spec = write("TableSpec.groovy", "class TableSpec extends Outer.Mid.Inner {}\n")

        val snapshot = compileClosed(spec)

        assertTrue(snapshot.messages().none { it.contains("unable to resolve class") }, "${snapshot.messages()}")
        assertEquals(listOf("Nest.groovy", "TableSpec.groovy"), snapshot.compiledFiles())
    }

    @Test
    fun `star imports of two packages declaring the class follow Groovy's order`(): Unit = runBlocking {
        write("a/DupA.groovy", "package a\nclass Dup {}\n")
        write("b/DupB.groovy", "package b\nclass Dup {}\n")
        val spec = write(
            "app/TableSpec.groovy",
            "package app\nimport a.*\nimport b.*\nclass TableSpec extends Dup {}\n",
        )

        val snapshot = compileClosed(spec)

        // Groovy tries the star imports in declaration order, so the first, a.Dup, is the one located.
        assertEquals(listOf("DupA.groovy", "TableSpec.groovy"), snapshot.compiledFiles(), "${snapshot.messages()}")
    }
}
