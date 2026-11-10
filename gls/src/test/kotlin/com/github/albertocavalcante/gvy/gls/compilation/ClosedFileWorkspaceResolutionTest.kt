package com.github.albertocavalcante.gvy.gls.compilation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A closed file reached through navigation is compiled on demand from disk (#1425). It must resolve a class
 * that only the workspace source list provides: here `Outer2.Inner`, declared in `Outers.groovy`, whose file
 * name matches neither class, so no by-name source lookup can find it.
 */
class ClosedFileWorkspaceResolutionTest {
    @TempDir
    lateinit var tempDir: Path

    private fun jarOf(type: Class<*>): Path = Path.of(type.protectionDomain.codeSource.location.toURI())

    @Test
    fun `a closed file compiled on demand resolves a class from a differently named source`(): Unit = runBlocking {
        val compilation = GroovyCompilationService(ioDispatcher = Dispatchers.Unconfined)
        val src = Files.createDirectories(tempDir.resolve("src"))
        Files.writeString(
            src.resolve("Outers.groovy"),
            "import spock.lang.*\n" +
                "class Outer1 { static abstract class Inner extends Specification {} }\n" +
                "class Outer2 { static abstract class Inner extends Specification {} }\n",
        )
        val spec = src.resolve("TableSpec.groovy")
        Files.writeString(spec, "class TableSpec extends Outer2.Inner {}\n")
        compilation.updateWorkspaceModel(
            tempDir,
            listOf(jarOf(spock.lang.Specification::class.java), jarOf(groovy.lang.GroovyObject::class.java)),
            listOf(src),
        )

        // Never opened: navigation reaches it through the content-less path, which compiles from disk.
        assertNotNull(compilation.ensureCompiled(spec.toUri()), "compiled from disk")
        val snapshot = assertNotNull(compilation.getCompiledSnapshot(spec.toUri()), "published")
        val messages = snapshot.parseResult.diagnostics.map { it.message }

        assertTrue(messages.none { it.contains("unable to resolve class Outer2.Inner") }, "$messages")
    }
}
