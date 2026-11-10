package com.github.albertocavalcante.gvy.jenkins.stubs

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JenkinsStubSetsTest {

    @TempDir
    lateinit var tempDir: Path

    private val setsRoot get() = tempDir.resolve("sets")
    private var now = Instant.parse("2026-11-10T12:00:00Z")
    private val writes = AtomicInteger()
    private val generator = JenkinsStubGenerator { file, content ->
        writes.incrementAndGet()
        file.writeText(content)
    }

    private fun sets(moveDirectory: (Path, Path) -> Unit = { from, to -> moveDirectoryAtomically(from, to) }) =
        JenkinsStubSets(setsRoot, generator, clock = { now }, moveDirectory = moveDirectory)

    private fun files(vararg steps: String) = mapOf(
        "org.example.CpsScript" to
            "package org.example\nclass CpsScript { ${steps.joinToString(" ") { "def $it() {}" }} }",
        "org.example.Fixed" to "package org.example\nclass Fixed {}",
    )

    private fun cpsScript(dir: Path) = dir.resolve("org/example/CpsScript.groovy").readText()

    private fun temporaryDirs() = Files.list(setsRoot).use { s -> s.filter { it.name.endsWith(".tmp") }.toList() }

    @Test
    fun `the same sources share one directory, written once`() {
        val first = sets().publish(files("aStep"))
        val writesAfterFirst = writes.get()
        val second = sets().publish(files("aStep"))

        assertEquals(first, second)
        assertEquals(writesAfterFirst, writes.get(), "an existing set is not rewritten")
        assertEquals(setsRoot.resolve(sets().keyOf(files("aStep"))), first)
    }

    @Test
    fun `different sources get different directories with their own content`() {
        val a = sets().publish(files("aStep"))
        val b = sets().publish(files("bStep"))

        assertNotEquals(a, b)
        assertTrue("aStep" in cpsScript(a) && "bStep" !in cpsScript(a))
        assertTrue("bStep" in cpsScript(b) && "aStep" !in cpsScript(b))
    }

    @Test
    fun `concurrent publishers only ever see complete sets and leave no temporary directories`() {
        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val results = (0 until threads * 4).map { i ->
                pool.submit<Pair<String, Path>> {
                    start.await()
                    val step = "step${i % 3}" // the same and different sets at once
                    step to sets().publish(files(step))
                }
            }
            start.countDown()
            results.map { it.get(30, TimeUnit.SECONDS) }.forEach { (step, dir) ->
                assertEquals(files(step).getValue("org.example.CpsScript"), cpsScript(dir))
                assertTrue(Files.isRegularFile(dir.resolve("org/example/Fixed.groovy")))
            }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(emptyList(), temporaryDirs())
        assertEquals(3, Files.list(setsRoot).use { it.count() })
    }

    @Test
    fun `a writer that loses the rename race uses the winner's directory`() {
        val winner = sets()
        val loser = sets { from, to ->
            winner.publish(files("aStep")) // another writer publishes the same key first
            moveDirectoryAtomically(from, to)
        }

        val dir = loser.publish(files("aStep"))

        assertEquals("def aStep() {}", cpsScript(dir).substringAfter("{ ").substringBefore(" }"))
        assertEquals(emptyList(), temporaryDirs())
    }

    @Test
    fun `without an atomic rename the set is still published by a plain move`() {
        val dir = sets { from, to ->
            moveDirectoryAtomically(from, to) { _, _ -> throw AtomicMoveNotSupportedException(null, null, "test") }
        }.publish(files("aStep"))

        assertTrue("aStep" in cpsScript(dir))
        assertEquals(emptyList(), temporaryDirs())
    }

    @Test
    fun `a set that cannot be written leaves nothing behind and falls through to the next candidate`() {
        val failing = JenkinsStubSets(
            setsRoot,
            JenkinsStubGenerator { file, content ->
                if ("aStep" in content) throw java.nio.file.AccessDeniedException(file.toString())
                file.writeText(content)
            },
            clock = { now },
        )

        val dir = failing.publishFirst({ files("aStep") }, { files() })

        assertEquals(failing.keyOf(files()), dir?.name)
        assertFalse(Files.exists(setsRoot.resolve(failing.keyOf(files("aStep")))))
        assertEquals(emptyList(), temporaryDirs())
    }

    @Test
    fun `eviction keeps live and recently used sets and drops the least recently used beyond the bound`() {
        val keys = (0 until JenkinsStubSets.MAX_SETS + 4).map { i ->
            now = now.plus(Duration.ofMinutes(1))
            sets().publish(files("s$i")).name
        }
        // Everything was used more than the grace period ago, except the last two.
        now = now.plus(JenkinsStubSets.GRACE).minus(Duration.ofSeconds(90))
        val live = setOf(keys[0])

        sets().evict(live)

        val kept = Files.list(setsRoot).use { s -> s.map { it.name }.toList() }.toSet()
        assertTrue(keys[0] in kept, "a live set is never evicted")
        assertTrue(keys.takeLast(2).all { it in kept }, "recently used sets survive the bound")
        assertEquals(JenkinsStubSets.MAX_SETS, kept.size)
        assertFalse(keys[1] in kept, "the least recently used set beyond the bound goes")
    }

    @Test
    fun `eviction removes crashed writers' temporary directories once they are older than the grace period`() {
        sets().publish(files("aStep"))
        val stale = setsRoot.resolve(".deadbeef.crashed.tmp").createDirectories()
        stale.resolve("half.groovy").writeText("class Half {")
        Files.setLastModifiedTime(stale, FileTime.from(now.minus(JenkinsStubSets.GRACE).minusSeconds(1)))
        val fresh = setsRoot.resolve(".cafebabe.writing.tmp").createDirectories()
        Files.setLastModifiedTime(fresh, FileTime.from(now))

        sets().evict(emptySet())

        assertFalse(Files.exists(stale), "a stale temporary directory is removed")
        assertTrue(Files.exists(fresh), "a writer still in progress keeps its temporary directory")
    }
}
