// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.sources.GroovySourceResolver.Companion.GDK_CLASSES
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Only a failing run waits this long; a passing one never reaches it. */
private const val SAFETY_NET_SECONDS = 30L

/** Indexed after `DefaultGroovyMethods`; it holds the String extensions such as `takeAfter`. */
private const val HELD = "org.codehaus.groovy.runtime.StringGroovyMethods"
private const val STRING = "java.lang.String"

/**
 * The GDK index is built whole, once, before any lookup reads it. The build is held open with a latch where it
 * loads [HELD], so a lookup that read the part built so far would miss the String extensions.
 */
@Timeout(SAFETY_NET_SECONDS)
class GroovyGdkProviderIndexBuildTest {
    private val holding = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val gdkLoads = ConcurrentHashMap<String, Int>()
    private val delegate = JvmClasspathReflection { ClassLoader.getSystemClassLoader() }
    private val reflection = object : ClasspathReflection by delegate {
        override fun loadClass(className: String): Class<*>? {
            if (className in GDK_CLASSES) gdkLoads.merge(className, 1, Int::plus)
            if (className == HELD) {
                holding.countDown()
                check(release.await(SAFETY_NET_SECONDS, TimeUnit.SECONDS)) { "the index build was never released" }
            }
            return delegate.loadClass(className)
        }
    }

    @AfterEach
    fun tearDown() {
        release.countDown()
    }

    @Test
    fun `a lookup during the build waits for the whole index`() {
        val complete = GroovyGdkProvider(ClasspathService()).getMethodsForType(STRING)
        assertThat(complete.map { it.name }).describedAs("String extensions from $HELD").contains("takeAfter")
        val provider = GroovyGdkProvider(ClasspathService(reflectionProvider = reflection))

        val builder = thread(name = "gdk-index-builder") { provider.initialize() }
        assertThat(holding.await(SAFETY_NET_SECONDS, TimeUnit.SECONDS)).describedAs("the build reached $HELD").isTrue()
        val seen = AtomicReference<List<GdkExtensionMethod>>()
        val reader = thread(name = "gdk-index-reader") { seen.set(provider.getMethodsForType(STRING)) }
        awaitReturnedOrBlockedBy(reader, builder)
        release.countDown()
        reader.join()
        builder.join()

        assertThat(seen.get()).containsExactlyInAnyOrderElementsOf(complete)
    }

    @Test
    fun `the index is built once however many warm-ups and lookups run`() {
        release.countDown()
        val provider = GroovyGdkProvider(ClasspathService(reflectionProvider = reflection))

        repeat(2) {
            provider.initialize()
            provider.getMethodsForType(STRING)
            provider.getQualifiedMethodsForTypes(listOf(STRING, "java.util.List"))
        }

        assertThat(gdkLoads).isEqualTo(GDK_CLASSES.associateWith { 1 })
    }

    /**
     * Waits until [reader] has returned or is blocked on a monitor [builder] holds: while the build is held, those are
     * the only places it can stop. It waits on thread state, so no sleep or timeout decides the outcome. It reads
     * `Thread.id` because `Thread.threadId()` is JDK 19+ and these tests also run on 17.
     */
    @Suppress("DEPRECATION")
    private fun awaitReturnedOrBlockedBy(reader: Thread, builder: Thread) {
        val threads = ManagementFactory.getThreadMXBean()
        while (reader.isAlive && threads.getThreadInfo(reader.id)?.lockOwnerId != builder.id) Thread.onSpinWait()
    }
}
