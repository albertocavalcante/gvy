package com.github.albertocavalcante.gvy.gls.providers.codeaction

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Import candidates come from the workspace class-name lookup, for closed and unsaved files alike (#1312). */
class ImportActionWorkspaceLookupTest {
    @TempDir
    lateinit var tempDir: Path

    private val compilation = spyk(GroovyCompilationService())
    private val action = ImportAction(compilation)
    private val target = "file:///Target.groovy"

    @AfterEach
    fun close() = compilation.clearCaches()

    @Test
    fun `a class in a closed workspace file is offered`(): Unit = runBlocking {
        val closed = tempDir.resolve("Greeter.groovy").apply { writeText("package app\nclass Greeter {}") }.toUri()
        compilation.symbolIndexingService.indexFile(closed)

        assertEquals(listOf("Import 'app.Greeter'"), titles("Greeter"))
    }

    @Test
    fun `a class in an unsaved buffer is offered and its old name is not`(): Unit = runBlocking {
        val buffer = tempDir.resolve("Draft.groovy").toUri() // never written to disk
        assertTrue(compilation.compile(buffer, "package buf\nclass Draft {}").isSuccess)
        assertEquals(listOf("Import 'buf.Draft'"), titles("Draft"))

        assertTrue(compilation.compile(buffer, "package buf\nclass Renamed {}").isSuccess)

        assertTrue(titles("Draft").isEmpty(), "a renamed class must not be offered")
        assertEquals(listOf("Import 'buf.Renamed'"), titles("Renamed"))
    }

    @Test
    fun `an ambiguous or unpackaged name is declined`(): Unit = runBlocking {
        listOf("a", "b").forEach { pkg ->
            val uri = tempDir.resolve("$pkg.groovy").apply { writeText("package $pkg\nclass Shared {}") }.toUri()
            compilation.symbolIndexingService.indexFile(uri)
        }
        val loose = tempDir.resolve("Loose.groovy").apply { writeText("class Loose {}") }.toUri()
        compilation.symbolIndexingService.indexFile(loose)

        assertTrue(titles("Shared").isEmpty(), "two candidates are ambiguous")
        assertTrue(titles("Loose").isEmpty(), "a default-package class cannot be imported")
    }

    @Test
    fun `a class differing only in case is neither offered nor ambiguous`(): Unit = runBlocking {
        // The name index folds case for prefix lookups; an exact lookup still compares names exactly.
        val upper = tempDir.resolve("Upper.groovy").apply { writeText("package app\nclass Greeter {}") }.toUri()
        val lower = tempDir.resolve("Lower.groovy").apply { writeText("package low\nclass greeter {}") }.toUri()
        listOf(upper, lower).forEach { compilation.symbolIndexingService.indexFile(it) }

        assertEquals(listOf("Import 'app.Greeter'"), titles("Greeter"))
        assertEquals(listOf("Import 'low.greeter'"), titles("greeter"))
        assertTrue(titles("GREETER").isEmpty())
    }

    @Test
    fun `a lowercase class is no candidate for its capitalized name`(): Unit = runBlocking {
        val lower = tempDir.resolve("Foo.groovy").apply { writeText("package low\nclass foo {}") }.toUri()
        compilation.symbolIndexingService.indexFile(lower)

        assertTrue(titles("Foo").isEmpty())
    }

    @Test
    fun `the lookup does not scan every indexed file`(): Unit = runBlocking {
        val closed = tempDir.resolve("Greeter.groovy").apply { writeText("package app\nclass Greeter {}") }.toUri()
        compilation.symbolIndexingService.indexFile(closed)

        titles("Greeter")

        verify(exactly = 0) { compilation.getAllSymbolStorages() }
    }

    private fun titles(symbol: String): List<String> {
        val diagnostic = Diagnostic().apply {
            range = Range(Position(0, 0), Position(0, symbol.length))
            message = "unable to resolve class $symbol"
        }
        return action.createImportActions(target, listOf(diagnostic), "$symbol x = null").map { it.title }
    }
}
