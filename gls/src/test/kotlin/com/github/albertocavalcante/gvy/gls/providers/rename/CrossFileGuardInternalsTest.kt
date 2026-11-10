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
import java.nio.file.attribute.FileTime
import java.time.Instant
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
            layout = WorkspaceLayout(
                sources = {
                    job.cancel()
                    sources
                },
                roots = { listOf(tempDir) },
            ),
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
        cache.rebind(listOf(tempDir))
        files(2).forEach { cache.read(it, 1_000) }
        assertEquals(2, cache.size)

        cache.rebind(listOf(tempDir))
        assertEquals(2, cache.size, "same root keeps the entries")
        cache.rebind(listOf(tempDir.resolve("other")))
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

    @Test
    fun `a walk is reused within the ttl and repeated after it`() {
        var now = 0L
        val cache = WorkspaceWalkCache(ttlMillis = 10_000, clock = { now })
        val walk = { Walked(emptyList(), overflow = false) }

        cache.get("k", walk)
        now = 9_999
        cache.get("k", walk)
        assertEquals(1, cache.walks)
        now = 10_000
        cache.get("k", walk)
        assertEquals(2, cache.walks)
        cache.get("other key", walk)
        assertEquals(3, cache.walks, "a different request is not served the old walk")
    }

    @Test
    fun `invalidating drops the walk`() {
        val cache = WorkspaceWalkCache()
        val walk = { Walked(emptyList(), overflow = false) }
        cache.get("k", walk)
        cache.invalidate()
        cache.get("k", walk)
        assertEquals(2, cache.walks)
    }

    @Test
    fun `a walk that an invalidation overtakes is stale on its first read`() {
        val cache = WorkspaceWalkCache()
        val walk = { Walked(emptyList(), overflow = false) }

        cache.get("k") {
            cache.invalidate() // lands after the walk started and before its result is stored
            walk()
        }
        cache.get("k", walk)

        assertEquals(2, cache.walks, "the pre-invalidation result was not served")
        cache.get("k", walk)
        assertEquals(2, cache.walks, "the re-walk is cached normally")
    }

    @Test
    fun `a walk is repeated when a directory it listed changed and not otherwise`() {
        val dir = Files.createDirectories(tempDir.resolve("pkg"))
        Files.writeString(dir.resolve("A.groovy"), "class A {}")
        val old = FileTime.from(Instant.now().minusSeconds(3_600))
        Files.setLastModifiedTime(dir, old)
        Files.setLastModifiedTime(tempDir, old)
        val cache = WorkspaceWalkCache(clock = { System.currentTimeMillis() + 60_000 })
        val walk = { walkWorkspace(listOf(tempDir), emptyList(), 100, 1_000) {} }

        cache.get("k", walk)
        cache.get("k", walk)
        assertEquals(1, cache.walks, "unchanged directories are served from the cache")

        Files.writeString(dir.resolve("Jenkinsfile"), "node {}")
        Files.setLastModifiedTime(dir, FileTime.from(old.toInstant().plusSeconds(10))) // timestamps can tie
        val found = cache.get("k", walk)
        assertEquals(2, cache.walks, "a file added under a walked directory forces a new walk")
        assertTrue(found.files.any { it.fileName.toString() == "Jenkinsfile" })

        Files.createDirectories(tempDir.resolve("pkg/sub"))
        Files.setLastModifiedTime(dir, FileTime.from(old.toInstant().plusSeconds(20)))
        cache.get("k", walk)
        assertEquals(3, cache.walks, "a new directory does too")

        Files.delete(dir.resolve("Jenkinsfile"))
        Files.setLastModifiedTime(dir, FileTime.from(old.toInstant().plusSeconds(30)))
        cache.get("k", walk)
        assertEquals(4, cache.walks, "and so does a deletion")
    }

    @Test
    fun `a directory modified just before the walk is not trusted`() {
        val dir = Files.createDirectories(tempDir.resolve("fresh"))
        Files.writeString(dir.resolve("A.groovy"), "class A {}")
        val cache = WorkspaceWalkCache()
        val walk = { walkWorkspace(listOf(tempDir), emptyList(), 100, 1_000) {} }

        cache.get("k", walk)
        cache.get("k", walk)

        assertEquals(2, cache.walks, "coarse timestamps cannot order a change this close to the walk")
    }

    @Test
    fun `with no workspace root open file buffers are candidates and other documents are not`() = runBlocking {
        val index = checkNotNull(GroovyCompilationService().getWorkspaceSymbolIndex())
        val caller = tempDir.resolve("B.groovy").toUri()
        val buffers = mapOf(
            caller to "class B { def run(x) { x.A() } }",
            java.net.URI.create("untitled:Untitled-1") to "println 'A'",
            tempDir.resolve("notes.txt").toUri() to "A",
        )
        val layout = WorkspaceLayout(sources = { emptyList() }, roots = { emptyList() })
        val guard = CrossFileUseGuard(index, { buffers }, layout)

        val uses = guard.usesOutside(tempDir.resolve("A.groovy").toUri(), target(), "A")

        assertEquals(listOf(caller), uses.mentioned)
    }
}
