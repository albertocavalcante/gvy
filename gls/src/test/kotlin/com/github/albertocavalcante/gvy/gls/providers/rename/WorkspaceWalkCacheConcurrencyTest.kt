// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import org.junit.jupiter.api.io.TempDir
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Walks that overlap in time: one walk per miss in flight, no stale overwrite, and a timestamp taken at the start. */
class WorkspaceWalkCacheConcurrencyTest {
    @TempDir
    lateinit var tempDir: Path

    private val empty = { Walked(emptyList(), overflow = false) }

    @Test
    fun `a miss while a walk is in flight waits for that walk instead of walking again`() {
        val cache = WorkspaceWalkCache()
        val held = HeldWalk()
        val leader = Caller("walk-leader") { cache.get("k", held::walk) }
        held.awaitWalking()

        val follower = Caller("walk-follower") { cache.get("k", empty) }
        awaitReturnedOrBlockedBy(follower.thread, leader.thread)
        held.release()

        assertSame(held.result, leader.get())
        assertSame(held.result, follower.get(), "the follower is served the walk it waited for")
        assertEquals(1, cache.walks, "concurrent misses share one walk")
    }

    @Test
    fun `a walk an invalidation overtook does not replace the walk after it`() {
        val cache = WorkspaceWalkCache()
        val held = HeldWalk()
        val stale = Caller("stale-walk") { cache.get("k", held::walk) }
        held.awaitWalking()
        cache.invalidate() // the files changed while the first walk ran: its result is out of date

        val fresh = Caller("fresh-walk") { cache.get("k", empty) }
        awaitReturnedOrBlockedBy(fresh.thread, stale.thread)
        held.release()
        stale.get()
        fresh.get()
        val walksBefore = cache.walks
        cache.get("k", empty)

        assertEquals(2, walksBefore, "the walk after the invalidation ran once")
        assertEquals(walksBefore, cache.walks, "and stays cached: the overtaken walk did not replace it")
    }

    @Test
    fun `a directory modified just before a long walk started is not trusted`() {
        val dir = Files.createDirectories(tempDir.resolve("pkg"))
        val modified = FileTime.fromMillis(MODIFIED_MILLIS)
        Files.setLastModifiedTime(dir, modified)
        var now = MODIFIED_MILLIS + WorkspaceWalkCache.RECENT_MILLIS / 2
        val cache = WorkspaceWalkCache(ttlMillis = TimeUnit.HOURS.toMillis(1), clock = { now })
        val walk = {
            now += LONG_WALK_MILLIS // the walk lists pkg/ at its start and ends much later
            Walked(emptyList(), overflow = false, directories = mapOf(dir to modified))
        }

        cache.get("k", walk)
        cache.get("k", walk)

        // pkg/ was listed in the same timestamp tick as its last change, so a later change in that tick is invisible
        assertEquals(2, cache.walks, "the walk's age counts from when it started listing")
    }

    /** A walk that signals when it starts and returns [result] only once [release] is called. */
    private class HeldWalk {
        val result = Walked(emptyList(), overflow = false)
        private val walking = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun walk(): Walked {
            walking.countDown()
            check(released.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the walk was never released" }
            return result
        }

        fun awaitWalking() = check(walking.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the walk never started" }

        fun release() = released.countDown()
    }

    /** Runs [body] on its own daemon thread, so a test can see whether that thread is blocked. */
    private class Caller(name: String, body: () -> Walked) {
        private val task = FutureTask<Walked> { body() }
        val thread = Thread(task, name).apply {
            isDaemon = true
            start()
        }

        fun get(): Walked = task.get(WAIT_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * Waits until [caller] has returned or is blocked on a monitor [owner] holds. It waits on thread state, so no
     * sleep decides the outcome; the deadline only ends a wait that would otherwise never finish. It reads
     * `Thread.id` because `Thread.threadId()` is JDK 19+ and these tests also run on 17.
     */
    @Suppress("DEPRECATION")
    private fun awaitReturnedOrBlockedBy(caller: Thread, owner: Thread) {
        val threads = ManagementFactory.getThreadMXBean()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (caller.isAlive && threads.getThreadInfo(caller.id)?.lockOwnerId != owner.id) {
            if (Thread.interrupted()) throw InterruptedException("interrupted while waiting for ${caller.name}")
            check(System.nanoTime() < deadline) { "${caller.name} neither returned nor blocked on ${owner.name}" }
            Thread.onSpinWait()
        }
        assertTrue(owner.isAlive, "${owner.name} finished before it was released")
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val MODIFIED_MILLIS = 1_700_000_000_000L
        const val LONG_WALK_MILLIS = 60_000L
    }
}
