package com.github.albertocavalcante.gvy.gls.services

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
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

    private fun currentLoader(service: ClasspathService): URLClassLoader {
        val field = ClasspathService::class.java.getDeclaredField("currentClassLoader").apply { isAccessible = true }
        val value = field.get(service)
        val loader = (value as? java.util.concurrent.atomic.AtomicReference<*>)?.get() ?: value
        assertTrue(loader is URLClassLoader, "expected a URLClassLoader, got $loader")
        return loader
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
