// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val TIMEOUT_SECONDS = 30L

/** An update returns in milliseconds; before the fix it waited for the whole held build. */
private const val UPDATE_TIMEOUT_SECONDS = 10L
private const val OLD_CLASSES = 3
private const val MANY_CLASSES = 200
private const val READERS = 4
private const val LOOKUPS = 50
private const val WAITERS = 3

/** Bounds the concurrent-updates writer, whose every update logs at INFO. */
private const val MAX_UPDATES = 1_000

private val PARKED = setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)

/**
 * Class-name lookups read the class index without a lock while a classpath update cleared it and the next build
 * refilled it, so a lookup could get an empty or half-built index or fail while copying it. The build held the
 * service monitor, so a classpath update waited for the whole build (#2474).
 */
class ClasspathClassIndexRaceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `a lookup that started before a classpath update gets the whole index of its classpath`() {
        val old = tempDir.resolve("old")
        val new = tempDir.resolve("new")
        val index = HeldIndex(
            mapOf(old.toString() to widgets("com.old", OLD_CLASSES), new.toString() to widgets("com.new", 1)),
            hold = true,
        )
        val service = ClasspathService(index)
        service.updateClasspath(listOf(old))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val lookup = executor.submit<List<String>> { widgetsOf(service) }
            assertTrue(index.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the lookup never started a build")

            // The update used to wait on the service monitor, which the build held until it finished.
            executor.submit { service.updateClasspath(listOf(new)) }.get(UPDATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            index.release.countDown()

            assertEquals(sortedNames(widgets("com.old", OLD_CLASSES)), lookup.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        } finally {
            index.release.countDown()
            executor.shutdownNow()
            service.close()
        }
    }

    @Test
    fun `a class index built for a replaced classpath is not published`() {
        val old = tempDir.resolve("old")
        val new = tempDir.resolve("new")
        val index = HeldIndex(
            mapOf(old.toString() to widgets("com.old", 1), new.toString() to widgets("com.new", 1)),
            hold = true,
        )
        val service = ClasspathService(index)
        service.updateClasspath(listOf(old))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val stale = executor.submit<List<String>> { widgetsOf(service) }
            assertTrue(index.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the lookup never started a build")
            executor.submit { service.updateClasspath(listOf(new)) }.get(UPDATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            index.release.countDown()
            stale.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertEquals(sortedNames(widgets("com.new", 1)), widgetsOf(service))
            assertEquals(listOf(listOf(old.toString()), listOf(new.toString())), index.builds.toList())
        } finally {
            index.release.countDown()
            executor.shutdownNow()
            service.close()
        }
    }

    @Test
    fun `lookups during a build wait for it instead of starting their own`() {
        val dir = tempDir.resolve("shared")
        val index = HeldIndex(mapOf(dir.toString() to widgets("com.shared", OLD_CLASSES)), hold = true)
        val service = ClasspathService(index)
        service.updateClasspath(listOf(dir))
        try {
            val builder = LookupThread { service.indexAllClasses() }
            assertTrue(index.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the build never started")
            val lookups = List(WAITERS) { LookupThread { fullNames(service.findClassesByPrefix("Widget")) } }
            lookups.forEach { it.awaitWaiting() }
            index.release.countDown()

            builder.get()
            lookups.forEach { assertEquals(sortedNames(widgets("com.shared", OLD_CLASSES)), it.get()) }
            assertEquals(1, index.builds.size, "builds run for one classpath")
        } finally {
            index.release.countDown()
            service.close()
        }
    }

    @Test
    fun `an Error in a build releases its waiters and the next lookup builds again`() {
        failHeldBuild(NoClassDefFoundError("com/example/Broken")) { lookup ->
            assertIs<NoClassDefFoundError>(assertFailsWith<ExecutionException> { lookup.get() }.cause)
        }
    }

    @Test
    fun `waiters of a failed build get no classes and the next lookup builds again`() {
        failHeldBuild(IllegalStateException("unreadable classpath entry")) { lookup ->
            assertEquals(emptyList(), lookup.get())
        }
    }

    @Test
    fun `concurrent lookups during classpath updates each get one whole index`() {
        val first = tempDir.resolve("first")
        val second = tempDir.resolve("second")
        val classes = mapOf(
            first.toString() to widgets("com.first", MANY_CLASSES),
            second.toString() to widgets("com.second", MANY_CLASSES),
        )
        val wholeIndexes = classes.values.map(::sortedNames)
        val service = ClasspathService(HeldIndex(classes, hold = false))
        service.updateClasspath(listOf(first))
        val start = CountDownLatch(1)
        val readersDone = CountDownLatch(READERS)
        val executor = Executors.newFixedThreadPool(READERS + 1)
        try {
            val writer = executor.submit {
                check(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                var updates = 0
                // Keep updating until every reader is done, so updates overlap the lookups. The await throws when
                // shutdownNow interrupts it, so a hung reader cannot keep the writer spinning.
                while (updates < MAX_UPDATES && !readersDone.await(0, TimeUnit.NANOSECONDS)) {
                    service.updateClasspath(listOf(if (updates++ % 2 == 0) second else first))
                }
            }
            val readers = List(READERS) {
                executor.submit {
                    try {
                        check(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        runBlocking {
                            repeat(LOOKUPS) {
                                val found = fullNames(service.findClassesByPrefix("Widget", MANY_CLASSES))
                                check(found in wholeIndexes) { "a lookup got ${found.size} of $MANY_CLASSES classes" }
                            }
                        }
                    } finally {
                        readersDone.countDown()
                    }
                }
            }
            start.countDown()
            readers.forEach { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            writer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            start.countDown()
            executor.shutdownNow()
            service.close()
        }
    }

    @Test
    fun `a lookup waiting for a build leaves its thread to other coroutines`() {
        val dir = tempDir.resolve("held")
        val index = HeldIndex(mapOf(dir.toString() to widgets("com.held", OLD_CLASSES)), hold = true)
        val service = ClasspathService(index)
        service.updateClasspath(listOf(dir))
        try {
            // runBlocking runs both coroutines on this one thread, so the test gets past yield() while the build is
            // held only if the lookup suspended. A lookup that ran the build or parked in join() would keep the thread.
            runBlocking {
                val lookup = async { fullNames(service.findClassesByPrefix("Widget")) }
                yield()
                assertTrue(index.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the build never started")
                assertTrue(lookup.isActive, "the lookup returned before its build was released")
                index.release.countDown()

                assertEquals(sortedNames(widgets("com.held", OLD_CLASSES)), lookup.await())
            }
        } finally {
            index.release.countDown()
            service.close()
        }
    }

    @Test
    fun `a cancelled lookup returns at once and leaves the build to the other lookups`() {
        val dir = tempDir.resolve("cancelled")
        val index = HeldIndex(mapOf(dir.toString() to widgets("com.cancelled", OLD_CLASSES)), hold = true)
        val service = ClasspathService(index)
        service.updateClasspath(listOf(dir))
        val expected = sortedNames(widgets("com.cancelled", OLD_CLASSES))
        try {
            runBlocking {
                val cancelled = async { fullNames(service.findClassesByPrefix("Widget")) }
                val waiter = async { fullNames(service.findClassesByPrefix("Widget")) }
                yield()
                assertTrue(index.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the build never started")

                // The build is still held, so the cancelled lookup can finish only by leaving it to the others.
                cancelled.cancelAndJoin()
                assertTrue(cancelled.isCancelled, "the cancelled lookup")
                assertTrue(waiter.isActive, "the other lookup returned before the build was released")
                index.release.countDown()

                assertEquals(expected, waiter.await(), "a lookup sharing the build with the cancelled one")
            }
            // Cancelling a lookup must not cancel the build it shared, or every later lookup would fail.
            assertEquals(expected, widgetsOf(service), "the next lookup")
            assertEquals(1, index.builds.size, "builds run for one classpath")
        } finally {
            index.release.countDown()
            service.close()
        }
    }

    @Test
    fun `a build claimed for a replaced classpath gives its lookups the current index`() {
        val old = tempDir.resolve("old")
        val new = tempDir.resolve("new")
        val index = HeldIndex(
            mapOf(old.toString() to widgets("com.old", 1), new.toString() to widgets("com.new", 1)),
            hold = false,
        )
        val builds = QueuedExecutor()
        val service = ClasspathService(index, reflectionProvider = null, buildExecutor = builds)
        service.updateClasspath(listOf(old))
        try {
            runBlocking {
                val stale = async { fullNames(service.findClassesByPrefix("Widget")) }
                yield()
                // The lookup claimed the old classpath's build, which is still queued when the update retires it.
                service.updateClasspath(listOf(new))
                builds.runNext()
                builds.runNext()

                assertEquals(sortedNames(widgets("com.new", 1)), stale.await())
            }
            assertEquals(listOf(listOf(new.toString())), index.builds.toList(), "only the new classpath is scanned")
            assertTrue(builds.isEmpty(), "a build is left in the queue")
        } finally {
            service.close()
        }
    }

    @Test
    fun `an Error in the current build reaches the lookups of a build claimed for a replaced classpath`() {
        val old = tempDir.resolve("old")
        val new = tempDir.resolve("new")
        val index = HeldIndex(
            mapOf(old.toString() to widgets("com.old", 1), new.toString() to widgets("com.new", 1)),
            hold = false,
            failure = NoClassDefFoundError("com/example/Broken"),
        )
        val builds = QueuedExecutor()
        val service = ClasspathService(index, reflectionProvider = null, buildExecutor = builds)
        service.updateClasspath(listOf(old))
        try {
            runBlocking {
                // Each lookup returns what it threw, so one failed lookup does not cancel the others.
                val stale = List(WAITERS) {
                    async { runCatching { service.findClassesByPrefix("Widget") }.exceptionOrNull() }
                }
                yield()
                service.updateClasspath(listOf(new))
                builds.runNext()
                builds.runNext()

                stale.forEach { assertIs<NoClassDefFoundError>(it.await()) }
            }
            assertEquals(listOf(listOf(new.toString())), index.builds.toList(), "only the new classpath is scanned")
            assertTrue(builds.isEmpty(), "a build is left in the queue")
        } finally {
            service.close()
        }
    }

    /**
     * Holds a build that then throws [failure] while the lookup that started it and a second lookup wait for it.
     * [checkLookup] checks what each of them saw, and the next lookup builds again.
     */
    private fun failHeldBuild(failure: Throwable, checkLookup: (LookupThread<List<String>>) -> Unit) {
        val dir = tempDir.resolve("failing")
        val index = HeldIndex(
            mapOf(dir.toString() to widgets("com.failing", OLD_CLASSES)),
            hold = true,
            failure = failure,
        )
        val service = ClasspathService(index)
        service.updateClasspath(listOf(dir))
        try {
            val builder = LookupThread { fullNames(service.findClassesByPrefix("Widget")) }
            assertTrue(index.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the build never started")
            val waiter = LookupThread { fullNames(service.findClassesByPrefix("Widget")) }
            waiter.awaitWaiting()
            index.release.countDown()

            checkLookup(builder)
            checkLookup(waiter)
            val next = widgetsOf(service)
            assertEquals(sortedNames(widgets("com.failing", OLD_CLASSES)), next, "the lookup after the failure")
            assertEquals(2, index.builds.size, "the failed build and the retry")
        } finally {
            index.release.countDown()
            service.close()
        }
    }
}

/** One `Widget` class in each of [count] packages under [prefix]. */
private fun widgets(prefix: String, count: Int): List<IndexedClass> =
    (1..count).map { IndexedClass("Widget", "$prefix.p$it.Widget") }

private fun sortedNames(classes: List<IndexedClass>): List<String> = classes.map { it.fullName }.sorted()

private fun fullNames(classes: List<ClassInfo>): List<String> = classes.map { it.fullName }

/** The `Widget` lookup, made from a thread that runs no coroutine. */
private fun widgetsOf(service: ClasspathService): List<String> =
    runBlocking { fullNames(service.findClassesByPrefix("Widget")) }

/** Runs [body] in [runBlocking] on its own daemon thread, so a test can see when that thread starts waiting. */
private class LookupThread<T>(body: suspend () -> T) {
    private val task = FutureTask<T> { runBlocking { body() } }
    private val thread = Thread(task, "class-index-lookup").apply {
        isDaemon = true
        start()
    }

    /**
     * Returns once the thread is parked. In these tests the only park is runBlocking's, once the lookup suspended on
     * the build another lookup started; a park sets a blocker, while a wait for class initialization does not.
     * runBlocking parks with a timeout, so the thread may be waiting with or without one.
     */
    fun awaitWaiting() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (thread.state !in PARKED || LockSupport.getBlocker(thread) == null) {
            check(System.nanoTime() < deadline) { "the lookup never waited for the build" }
            Thread.onSpinWait()
        }
    }

    fun get(): T = task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
}

/**
 * Queues class-index builds until the test runs them, one at a time on its own thread. Every lookup in the tests that
 * use it runs on that thread too, so the queue needs no lock.
 */
private class QueuedExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
        tasks.addLast(command)
    }

    fun runNext() {
        tasks.removeFirst().run()
    }

    fun isEmpty(): Boolean = tasks.isEmpty()
}

/**
 * Lists [classes] by classpath entry and records each build. With [hold], builds wait for [release]; the first build
 * then throws [failure] when one is given.
 */
private class HeldIndex(
    private val classes: Map<String, List<IndexedClass>>,
    hold: Boolean,
    private val failure: Throwable? = null,
) : ClasspathIndex {
    val started = CountDownLatch(1)
    val release = CountDownLatch(if (hold) 1 else 0)
    val builds: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())

    override fun index(classpathEntries: List<String>): List<IndexedClass> {
        val build = synchronized(builds) {
            builds.add(classpathEntries)
            builds.size
        }
        started.countDown()
        check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "the test never released the build" }
        if (build == 1 && failure != null) throw failure
        return classpathEntries.flatMap { classes[it].orEmpty() }
    }
}
