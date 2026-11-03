package com.github.albertocavalcante.gvy.gls.compilation

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drives the real compile and index path: the symbol index must follow the file, not the first read of it. */
class SymbolIndexStalenessTest {
    @TempDir
    lateinit var tempDir: Path

    private val service = GroovyCompilationService()

    private fun symbolNames(uri: URI): Set<String> =
        service.getAllSymbolStorages()[uri]?.getSymbols(uri)?.map { it.name }?.toSet().orEmpty()

    @Test
    fun `re-indexing a changed file replaces its symbols`(): Unit = runBlocking {
        val file = tempDir.resolve("Changing.groovy")
        Files.writeString(file, "class OldName {}")
        val uri = file.toUri()
        service.symbolIndexingService.indexFile(uri)
        assertTrue("OldName" in symbolNames(uri))

        Files.writeString(file, "class NewName {}")
        service.symbolIndexingService.indexFile(uri)

        val names = symbolNames(uri)
        assertTrue("NewName" in names, "new symbol visible: $names")
        assertTrue("OldName" !in names, "old symbol gone: $names")
    }

    @Test
    fun `a deleted file loses its symbols`(): Unit = runBlocking {
        val file = tempDir.resolve("Doomed.groovy")
        Files.writeString(file, "class Doomed {}")
        val uri = file.toUri()
        service.symbolIndexingService.indexFile(uri)
        assertTrue("Doomed" in symbolNames(uri))

        Files.delete(file)

        assertNull(service.symbolIndexingService.indexFile(uri))
        assertTrue(symbolNames(uri).isEmpty())
        assertNull(service.getSymbolStorage(uri))
    }

    @Test
    fun `outline survives more than 100 other files being indexed after a compile cache hit`(): Unit = runBlocking {
        val uri = URI.create("file:///Outlined.groovy")
        val content = "class Outlined { def method() {} }"
        service.compile(uri, content)
        assertNotNull(service.getSymbolStorage(uri))

        repeat(150) { i ->
            val other = tempDir.resolve("Other$i.groovy")
            Files.writeString(other, "class Other$i {}")
            service.symbolIndexingService.indexFile(other.toUri())
        }
        // Same content: a compile cache hit, which must still be able to serve the outline.
        service.compile(uri, content)

        val index = service.getSymbolStorage(uri)
        assertNotNull(index, "outline index present after cache hit")
        assertTrue("Outlined" in index.getSymbols(uri).map { it.name })
        assertEquals(151, service.getAllSymbolStorages().size)
    }
}
