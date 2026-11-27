// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.sources.GroovySourceResolver.Companion.GDK_CLASSES
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
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

/** In Groovy 3 but not in the server's Groovy 4, so only a workspace classpath can provide it. */
private const val DATE_GDK = "org.codehaus.groovy.runtime.DateGroovyMethods"
private const val ENCODING_GDK = "org.codehaus.groovy.runtime.EncodingGroovyMethods"

/** A GDK-shaped class whose one method takes a type that exists nowhere, so reflecting on it fails to link. */
private const val UNLINKABLE = "gvy.test.UnlinkableGdkMethods"
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

    @Test
    fun `a GDK class that fails to link drops only itself`() {
        val bytes = gdkClassBytes(UNLINKABLE, "broken", "(Ljava/lang/String;Lgvy/test/Missing;)V")
        val unlinkable = OneClassLoader(UNLINKABLE, bytes).loadClass(UNLINKABLE)
        assertThatThrownBy { unlinkable.methods }.isInstanceOf(NoClassDefFoundError::class.java)
        val failing = object : ClasspathReflection by delegate {
            override fun loadClass(className: String): Class<*>? = when (className) {
                DATE_GDK -> unlinkable
                ENCODING_GDK -> throw IncompatibleClassChangeError(className)
                else -> delegate.loadClass(className)
            }
        }
        val provider = GroovyGdkProvider(ClasspathService(reflectionProvider = failing))

        provider.initialize()

        val origins = listOf(STRING, "java.io.File", "java.util.List").flatMap { type ->
            provider.getMethodsForType(type).map { it.originClass }
        }
        assertThat(origins)
            .describedAs("GDK classes indexed before and after the two that fail")
            .contains("DefaultGroovyMethods", "StringGroovyMethods", "ResourceGroovyMethods")
            .contains("PluginDefaultGroovyMethods")
            .doesNotContain("UnlinkableGdkMethods", "EncodingGroovyMethods")
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

    /** Defines [name] from [bytes] and loads every other class through the test's own loader. */
    private class OneClassLoader(
        private val name: String,
        private val bytes: ByteArray,
    ) : ClassLoader(GroovyGdkProviderIndexBuildTest::class.java.classLoader) {
        override fun findClass(className: String): Class<*> =
            if (className == name) defineClass(className, bytes, 0, bytes.size) else super.findClass(className)
    }
}

/** A public class [name] with one empty `public static void` [method] of [descriptor], e.g. `(Ljava/util/Date;)V`. */
private fun gdkClassBytes(name: String, method: String, descriptor: String): ByteArray =
    ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, method, descriptor, null, null).apply {
            visitCode()
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        visitEnd()
    }.toByteArray()
