// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TIMEOUT_SECONDS = 30L
private const val OLD_CLASSES = 3
private const val MANY_CLASSES = 200
private const val READERS = 4
private const val LOOKUPS = 50
private const val UPDATES = 20

/**
 * Class-name lookups read the class index without a lock while a classpath update cleared it and the next build
 * refilled it, so a lookup could get an empty or half-built index or throw ConcurrentModificationException. The build
 * held the service monitor, so a classpath update waited for the whole build (#2474).
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
            val lookup = executor.submit<List<String>> { fullNames(service.findClassesByPrefix("Widget")) }
            assertTrue(index.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the lookup never started a build")

            // The update used to wait on the service monitor, which the build held until it finished.
            executor.submit { service.updateClasspath(listOf(new)) }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
            val stale = executor.submit<List<String>> { fullNames(service.findClassesByPrefix("Widget")) }
            assertTrue(index.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the lookup never started a build")
            executor.submit { service.updateClasspath(listOf(new)) }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            index.release.countDown()
            stale.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertEquals(sortedNames(widgets("com.new", 1)), fullNames(service.findClassesByPrefix("Widget")))
            assertEquals(listOf(listOf(old.toString()), listOf(new.toString())), index.builds.toList())
        } finally {
            index.release.countDown()
            executor.shutdownNow()
            service.close()
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
        val executor = Executors.newFixedThreadPool(READERS + 1)
        try {
            val writer = executor.submit {
                check(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                repeat(UPDATES) { service.updateClasspath(listOf(if (it % 2 == 0) second else first)) }
            }
            val readers = List(READERS) {
                executor.submit {
                    check(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    repeat(LOOKUPS) {
                        val found = fullNames(service.findClassesByPrefix("Widget", MANY_CLASSES))
                        check(found in wholeIndexes) { "a lookup got ${found.size} of $MANY_CLASSES classes" }
                    }
                }
            }
            start.countDown()
            writer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            readers.forEach { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            service.close()
        }
    }
}

/** One `Widget` class in each of [count] packages under [prefix]. */
private fun widgets(prefix: String, count: Int): List<IndexedClass> =
    (1..count).map { IndexedClass("Widget", "$prefix.p$it.Widget") }

private fun sortedNames(classes: List<IndexedClass>): List<String> = classes.map { it.fullName }.sorted()

private fun fullNames(classes: List<ClassInfo>): List<String> = classes.map { it.fullName }

/** Lists [classes] by classpath entry and records each build; with [hold], builds wait for [release]. */
private class HeldIndex(private val classes: Map<String, List<IndexedClass>>, hold: Boolean) : ClasspathIndex {
    val started = CountDownLatch(1)
    val release = CountDownLatch(if (hold) 1 else 0)
    val builds: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())

    override fun index(classpathEntries: List<String>): List<IndexedClass> {
        builds.add(classpathEntries)
        started.countDown()
        check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "the test never released the build" }
        return classpathEntries.flatMap { classes[it].orEmpty() }
    }
}
