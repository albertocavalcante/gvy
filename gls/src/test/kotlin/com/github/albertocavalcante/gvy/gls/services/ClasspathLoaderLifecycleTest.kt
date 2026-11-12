// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every classpath update created a new loader and left the old one open, keeping its jar files and metaspace
 * alive for the life of the server (audit H2).
 */
class ClasspathLoaderLifecycleTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `a captured request solver stays on its original classpath generation`() {
        val service = ClasspathService()
        try {
            service.updateClasspath(listOf(jarWithClass("first-version.jar", "Versioned", "public void before() {}")))
            service.acquireClasspath().use { lease ->
                assertEquals(listOf("before"), methodNames(lease.solver, "com.example.Versioned"))
                service.updateClasspath(
                    listOf(jarWithClass("second-version.jar", "Versioned", "public void after() {}")),
                )
                assertEquals(listOf("before"), methodNames(lease.solver, "com.example.Versioned"))
                assertTrue(lease.reflection.getMethods("com.example.Versioned").any { it.name == "before" })
                assertTrue(!lease.isCurrent())
            }
        } finally {
            service.close()
        }
    }

    @Test
    fun `an active reader retains its original loader across multiple updates`() {
        val service = ClasspathService()
        try {
            service.updateClasspath(listOf(jar("captured.jar", "captured.txt")))
            val capturedLoader = service.acquireClasspath().use { lease ->
                service.updateClasspath(listOf(jar("next.jar", "next.txt")))
                service.updateClasspath(listOf(jar("later.jar", "later.txt")))
                assertNotNull(
                    lease.classLoader.getResource("captured.txt"),
                    "an active reader needs its original loader",
                )
                lease.classLoader
            }
            assertNull(capturedLoader.getResource("captured.txt"), "retired loader closes after its last reader")
        } finally {
            service.close()
        }
    }

    @Test
    fun `two leases retain a retired loader until both close`() {
        val service = ClasspathService()
        val first = jar("shared.jar", "shared.txt")
        service.updateClasspath(listOf(first))
        val one = service.acquireClasspath()
        val two = service.acquireClasspath()
        val loader = one.classLoader
        try {
            service.updateClasspath(listOf(jar("replacement.jar", "replacement.txt")))
            service.updateClasspath(listOf(jar("replacement2.jar", "replacement2.txt")))
            one.close()
            one.close()
            assertNotNull(loader.getResource("shared.txt"))
            two.close()
            assertNull(loader.getResource("shared.txt"))
        } finally {
            one.close()
            two.close()
            service.close()
        }
    }

    @Test
    fun `service close waits for a held lease and use releases on exceptions and cancellation`() {
        val failures = listOf(IllegalStateException("request failed"), CancellationException("cancelled"))
        failures.forEachIndexed { index, failure ->
            val service = ClasspathService()
            service.updateClasspath(listOf(jar("closing$index.jar", "closing.txt")))
            val lease = service.acquireClasspath()
            val loader = lease.classLoader
            service.close()
            assertNotNull(loader.getResource("closing.txt"))
            val observed = kotlin.test.assertFailsWith<Exception> { lease.use { throw failure } }
            assertTrue(observed === failure)
            assertNull(loader.getResource("closing.txt"))
            assertTrue(!lease.isCurrent())
        }
    }

    @Test
    fun `concurrent replacements publish a matching loader and solver`() {
        val service = ClasspathService()
        val first = jarWithClass("atomic-first.jar", "Versioned", "public void before() {}")
        val second = jarWithClass("atomic-second.jar", "Versioned", "public void after() {}")
        service.updateClasspath(listOf(first))
        val start = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writer = executor.submit {
                check(start.await(30, TimeUnit.SECONDS))
                repeat(10) { service.updateClasspath(listOf(if (it % 2 == 0) second else first)) }
            }
            start.countDown()
            repeat(10) {
                service.acquireClasspath().use { lease ->
                    val expected = if (lease.classpath == listOf(first)) "before" else "after"
                    assertEquals(listOf(expected), methodNames(lease.solver, "com.example.Versioned"))
                    val methods = lease.reflection.getMethods("com.example.Versioned").map { it.name }
                    assertTrue(expected in methods)
                }
            }
            writer.get(30, TimeUnit.SECONDS)
        } finally {
            start.countDown()
            executor.shutdownNow()
            service.close()
        }
    }

    @Test
    fun `an acquired lease survives concurrent service shutdown`() {
        val service = ClasspathService()
        service.updateClasspath(listOf(jar("shutdown.jar", "shutdown.txt")))
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val reader = executor.submit<Boolean> {
                service.acquireClasspath().use { lease ->
                    entered.countDown()
                    check(resume.await(30, TimeUnit.SECONDS))
                    lease.classLoader.getResource("shutdown.txt") != null
                }
            }
            assertTrue(entered.await(30, TimeUnit.SECONDS))
            service.close()
            resume.countDown()
            assertTrue(reader.get(30, TimeUnit.SECONDS))
        } finally {
            resume.countDown()
            executor.shutdownNow()
            service.close()
        }
    }

    @Test
    fun `lease records immutable classpath order and generation identity`() {
        val service = ClasspathService()
        val first = jar("ordered1.jar", "first.txt")
        val second = jar("ordered2.jar", "second.txt")
        try {
            service.updateClasspath(listOf(first, second))
            service.acquireClasspath().use { lease ->
                assertEquals(listOf(first, second), lease.classpath)
                assertTrue(lease.isCurrent())
                kotlin.test.assertFailsWith<UnsupportedOperationException> {
                    (lease.classpath as MutableList<Path>).clear()
                }
                service.updateClasspath(listOf(second, first))
                assertTrue(!lease.isCurrent())
                assertEquals(listOf(first, second), lease.classpath)
                service.acquireClasspath().use { next -> assertEquals(listOf(second, first), next.classpath) }
            }
        } finally {
            service.close()
        }
    }

    @Test
    fun `a classpath update closes the loader it replaces`() {
        val service = ClasspathService()
        service.updateClasspath(listOf(jar("first.jar", "first.txt")))
        val first = service.getTypeSolver().let { currentLoader(service) }
        assertNotNull(first.getResource("first.txt"), "control: the loader serves its jar")

        service.updateClasspath(listOf(jar("second.jar", "second.txt")))
        service.updateClasspath(listOf(jar("third.jar", "third.txt")))

        assertNull(first.getResource("first.txt"), "the replaced loader is still open")
        assertNotNull(currentLoader(service).getResource("third.txt"))
    }

    @Test
    fun `a long-lived type solver follows classpath updates`() {
        val service = ClasspathService()
        service.updateClasspath(listOf(jarWithClass("a.jar", "Alpha")))
        // Resolvers keep the solver for the whole session (hover, inlay hints, definition)
        val solver = service.getTypeSolver()
        assertTrue(solver.tryToSolveType("com.example.Alpha").isSolved, "control: resolves the first jar")

        service.updateClasspath(listOf(jarWithClass("b.jar", "Beta")))
        service.updateClasspath(listOf(jarWithClass("b2.jar", "Beta")))

        assertTrue(solver.tryToSolveType("com.example.Beta").isSolved, "the kept solver still uses a retired loader")
    }

    @Test
    fun `a type the kept solver could not resolve resolves once its jar is added`() {
        val service = ClasspathService()
        val solver = service.getTypeSolver()
        assertTrue(!solver.tryToSolveType("com.example.Gamma").isSolved, "control: not on the classpath yet")

        service.updateClasspath(listOf(jarWithClass("gamma.jar", "Gamma")))

        assertTrue(solver.tryToSolveType("com.example.Gamma").isSolved, "an unresolved lookup was cached for good")
    }

    @Test
    fun `the kept solver shows the members of the new jar version`() {
        val service = ClasspathService()
        service.updateClasspath(listOf(jarWithClass("v1.jar", "Versioned", "public void before() {}")))
        val solver = service.getTypeSolver()
        assertEquals(listOf("before"), methodNames(solver, "com.example.Versioned"))

        service.updateClasspath(listOf(jarWithClass("v2.jar", "Versioned", "public void after() {}")))

        assertEquals(listOf("after"), methodNames(solver, "com.example.Versioned"), "stale members of jar v1")
    }

    private fun methodNames(solver: com.github.albertocavalcante.groovyparser.resolution.TypeSolver, name: String) =
        solver.solveType(name).getDeclaredMethods().map { it.name }

    @Test
    fun `after close the kept solver no longer resolves from the closed classpath`() {
        val service = ClasspathService()
        service.updateClasspath(listOf(jarWithClass("delta.jar", "Delta")))
        val solver = service.getTypeSolver()
        assertTrue(solver.tryToSolveType("com.example.Delta").isSolved, "control")

        service.close()

        assertTrue(!solver.tryToSolveType("com.example.Delta").isSolved, "the solver still uses the closed loader")
        assertTrue(solver.tryToSolveType("java.lang.String").isSolved, "it falls back to the system classpath")
    }

    @Test
    fun `a replaced loader stays usable for one more update`() {
        val service = ClasspathService()
        service.updateClasspath(listOf(jar("first.jar", "first.txt")))
        val first = currentLoader(service)
        service.updateClasspath(listOf(jar("second.jar", "second.txt")))

        assertNotNull(first.getResource("first.txt"), "in-flight reflection on the replaced loader must not fail")

        service.updateClasspath(listOf(jar("third.jar", "third.txt")))
        assertNull(first.getResource("first.txt"), "closed once its successor is replaced too")
    }

    /** A jar with a compiled `com.example.<name>`. */
    private fun jarWithClass(jarName: String, className: String, body: String = ""): Path {
        val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
        org.junit.jupiter.api.Assumptions.assumeTrue(compiler != null, "needs a JDK compiler")
        val sources = Files.createDirectories(tempDir.resolve("$jarName-src/com/example"))
        val classes = Files.createDirectories(tempDir.resolve("$jarName-classes"))
        val source = Files.writeString(
            sources.resolve("$className.java"),
            "package com.example; public class $className { $body }",
        )
        check(compiler.run(null, null, null, "-d", classes.toString(), source.toString()) == 0) { "javac failed" }
        val path = tempDir.resolve(jarName)
        JarOutputStream(Files.newOutputStream(path)).use { out ->
            out.putNextEntry(JarEntry("com/example/$className.class"))
            Files.copy(classes.resolve("com/example/$className.class"), out)
            out.closeEntry()
        }
        return path
    }

    private fun currentLoader(service: ClasspathService): URLClassLoader = service.acquireClasspath().use {
        it.classLoader as URLClassLoader
    }

    private fun jar(name: String, resource: String): Path {
        val path = tempDir.resolve(name)
        JarOutputStream(Files.newOutputStream(path)).use { out ->
            out.putNextEntry(JarEntry(resource))
            out.write("present".toByteArray())
            out.closeEntry()
        }
        return path
    }
}
