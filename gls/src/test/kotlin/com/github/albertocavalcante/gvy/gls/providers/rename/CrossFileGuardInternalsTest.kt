package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cancellation and cache bounds of the cross-file check. */
class CrossFileGuardInternalsTest {
    @TempDir
    lateinit var tempDir: Path

    private fun files(count: Int): List<Path> = (0 until count).map {
        tempDir.resolve("F$it.groovy").also { file -> Files.writeString(file, "class F$it {}") }
    }

    @Test
    fun `a cancelled scan stops before reading any file`() = runBlocking {
        val sources = files(5)
        val cache = WorkspaceTextCache()
        val index = checkNotNull(GroovyCompilationService().getWorkspaceSymbolIndex())
        lateinit var job: Job
        var outcome: CrossFileUses? = null
        val guard = CrossFileUseGuard(
            index = index,
            openBuffers = { emptyMap() },
            workspaceSources = {
                job.cancel()
                sources
            },
            workspaceRoot = { tempDir },
            diskText = cache,
        )
        job = launch { outcome = guard.usesOutside(tempDir.resolve("A.groovy").toUri(), target(), "A") }
        job.join()

        assertTrue(job.isCancelled)
        assertNull(outcome)
        assertEquals(0, cache.size, "no file was read after cancellation")
    }

    private fun target() = ClassNode("A", 1, ClassHelper.OBJECT_TYPE)

    @Test
    fun `the cache is bounded by total characters`() {
        val cache = WorkspaceTextCache(maxEntries = 100, maxChars = 30)
        val sources = files(5) // each "class Fn {}" is 11 chars
        sources.forEach { assertIs<DiskText.Text>(cache.read(it, 1_000)) }

        assertTrue(cache.chars <= 30, "chars=${cache.chars}")
        assertEquals(2, cache.size)
    }

    @Test
    fun `the cache is cleared when the workspace root changes`() {
        val cache = WorkspaceTextCache()
        cache.rebind(tempDir)
        files(2).forEach { cache.read(it, 1_000) }
        assertEquals(2, cache.size)

        cache.rebind(tempDir)
        assertEquals(2, cache.size, "same root keeps the entries")
        cache.rebind(tempDir.resolve("other"))
        assertEquals(0, cache.size)
        assertEquals(0, cache.chars)
    }

    @Test
    fun `a file modified within two seconds is re-read even when its stamp is unchanged`() {
        val cache = WorkspaceTextCache()
        val file = files(1).single()
        val stamp = Files.getLastModifiedTime(file)
        assertEquals("class F0 {}", assertIs<DiskText.Text>(cache.read(file, 1_000)).text)

        Files.writeString(file, "class G0 {}") // same size
        Files.setLastModifiedTime(file, stamp) // same mtime: only the recency rule can notice
        assertEquals("class G0 {}", assertIs<DiskText.Text>(cache.read(file, 1_000)).text)
    }

    @Test
    fun `a file not modified recently is served from the cache`() {
        val file = files(1).single()
        val stamp = Files.getLastModifiedTime(file)
        val cache = WorkspaceTextCache(clock = { stamp.toMillis() + 60_000 })
        cache.read(file, 1_000)

        Files.writeString(file, "class G0 {}")
        Files.setLastModifiedTime(file, stamp)
        assertEquals("class F0 {}", assertIs<DiskText.Text>(cache.read(file, 1_000)).text)
    }

    @Test
    fun `undecodable bytes are replaced rather than making the file unreadable`() {
        val cache = WorkspaceTextCache()
        val file = tempDir.resolve("L.groovy")
        Files.write(file, "// caf\u00e9\nclass L {}\n".toByteArray(Charsets.ISO_8859_1))

        val text = assertIs<DiskText.Text>(cache.read(file, 1_000)).text
        assertTrue("class L {}" in text)
        assertEquals(1, cache.size, "the file is cached")
    }
}
