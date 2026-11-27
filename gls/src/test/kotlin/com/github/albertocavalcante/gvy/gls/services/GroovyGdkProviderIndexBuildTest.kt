// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.sources.GroovySourceResolver.Companion.GDK_CLASSES
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Only a failing run waits this long; a passing one never reaches it. */
private const val SAFETY_NET_SECONDS = 30L

/** Bounds each wait inside a test, below [SAFETY_NET_SECONDS] so that a stuck wait says what it waited for. */
private const val WAIT_SECONDS = 10L

/** Indexed after `DefaultGroovyMethods`; it holds the String extensions such as `takeAfter`. */
private const val HELD = "org.codehaus.groovy.runtime.StringGroovyMethods"

/** In Groovy 3 but not in the server's Groovy 4, so only a workspace classpath can provide it. */
private const val DATE_GDK = "org.codehaus.groovy.runtime.DateGroovyMethods"
private const val ENCODING_GDK = "org.codehaus.groovy.runtime.EncodingGroovyMethods"

/** A GDK-shaped class whose one method takes a type that exists nowhere, so reflecting on it fails to link. */
private const val UNLINKABLE = "gvy.test.UnlinkableGdkMethods"
private const val STRING = "java.lang.String"
private const val DATE = "java.util.Date"
private const val WORKSPACE_ONLY = "workspaceOnly"

/**
 * Each GDK index is built whole before any lookup reads it, once per classpath. Where a build is held open, a latch
 * holds it where it loads [HELD], so a lookup that read the part built so far would miss the String extensions.
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
                check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the index build was never released" }
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

        val build = FutureTask<Unit> { provider.initialize() }
        val builder = thread(name = "gdk-index-builder", block = build::run)
        assertThat(holding.await(WAIT_SECONDS, TimeUnit.SECONDS)).describedAs("the build reached $HELD").isTrue()
        val read = FutureTask<List<GdkExtensionMethod>> { provider.getMethodsForType(STRING) }
        val reader = thread(name = "gdk-index-reader", block = read::run)
        awaitReturnedOrBlockedBy(reader, builder)
        release.countDown()

        // get() rethrows, as an ExecutionException, whatever either thread threw
        assertThat(read.get(WAIT_SECONDS, TimeUnit.SECONDS)).containsExactlyInAnyOrderElementsOf(complete)
        build.get(WAIT_SECONDS, TimeUnit.SECONDS)
    }

    @Test
    fun `the index is built once per classpath however many warm-ups and lookups run`() {
        release.countDown()
        val classpath = ClasspathService(reflectionProvider = reflection)
        val provider = GroovyGdkProvider(classpath)
        fun warmUpAndLookUpTwice() {
            repeat(2) {
                provider.initialize()
                provider.getMethodsForType(STRING)
                provider.getQualifiedMethodsForTypes(listOf(STRING, "java.util.List"))
            }
        }

        warmUpAndLookUpTwice()
        assertThat(gdkLoads).describedAs("one build for the first classpath").isEqualTo(GDK_CLASSES.associateWith { 1 })

        classpath.updateClasspath(emptyList())
        provider.onClasspathChanged()
        warmUpAndLookUpTwice()
        assertThat(gdkLoads).describedAs("one rebuild for the change").isEqualTo(GDK_CLASSES.associateWith { 2 })
    }

    @Test
    fun `a classpath change rebuilds the index from the new classpath`(@TempDir workspace: Path) {
        val classpath = ClasspathService()
        val provider = GroovyGdkProvider(classpath)
        try {
            // A lookup before dependency resolution builds the index from the server's own Groovy, which lacks it
            assertThat(provider.getMethodsForType(DATE).map { it.originClass }).doesNotContain("DateGroovyMethods")

            val classFile = workspace.resolve(DATE_GDK.replace('.', '/') + ".class")
            Files.createDirectories(classFile.parent)
            Files.write(classFile, gdkClassBytes(DATE_GDK, WORKSPACE_ONLY, "(Ljava/util/Date;)V"))
            classpath.updateClasspath(listOf(workspace))
            provider.onClasspathChanged()

            val fromWorkspace = provider.getMethodsForType(DATE).filter { it.originClass == "DateGroovyMethods" }
            assertThat(fromWorkspace.map { it.name })
                .describedAs("$DATE_GDK from the workspace")
                .containsExactly(WORKSPACE_ONLY)
        } finally {
            classpath.close() // releases the loader over the workspace before @TempDir deletes it
        }
    }

    @Test
    fun `a lookup reads one index even when the classpath changes during it`() {
        val complete = GroovyGdkProvider(ClasspathService()).getMethodsForType(STRING)
        lateinit var provider: GroovyGdkProvider
        var classpathHasHeld = true
        val swapping = object : ClasspathReflection by delegate {
            override fun loadClass(className: String): Class<*>? {
                // A lookup loads its receiver after reading the receiver's entries and before its supertypes'
                if (className == STRING && classpathHasHeld) {
                    classpathHasHeld = false
                    provider.onClasspathChanged()
                }
                return if (className == HELD && !classpathHasHeld) null else delegate.loadClass(className)
            }
        }
        provider = GroovyGdkProvider(ClasspathService(reflectionProvider = swapping))
        provider.initialize()

        assertThat(provider.getMethodsForType(STRING)).containsExactlyInAnyOrderElementsOf(complete)
        assertThat(provider.getMethodsForType(STRING).map { it.originClass })
            .describedAs("the index the change installed, built without $HELD")
            .doesNotContain("StringGroovyMethods")
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
     * the only places it can stop. It waits on thread state, so no sleep decides the outcome; the deadline and the
     * interrupt check only end a wait that would otherwise never finish, as a reader parked on a latch would. It
     * reads `Thread.id` because `Thread.threadId()` is JDK 19+ and these tests also run on 17.
     */
    @Suppress("DEPRECATION")
    private fun awaitReturnedOrBlockedBy(reader: Thread, builder: Thread) {
        val threads = ManagementFactory.getThreadMXBean()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (reader.isAlive && threads.getThreadInfo(reader.id)?.lockOwnerId != builder.id) {
            if (Thread.interrupted()) throw InterruptedException("interrupted while waiting for ${reader.name}")
            check(System.nanoTime() < deadline) { "${reader.name} neither returned nor blocked on ${builder.name}" }
            Thread.onSpinWait()
        }
    }

    /** Defines [definedName] from [bytes] and loads every other class through the test's own loader. */
    private class OneClassLoader(private val definedName: String, private val bytes: ByteArray) :
        ClassLoader(GroovyGdkProviderIndexBuildTest::class.java.classLoader) {
        override fun findClass(className: String): Class<*> =
            if (className == definedName) defineClass(className, bytes, 0, bytes.size) else super.findClass(className)
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
