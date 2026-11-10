package com.github.albertocavalcante.gvy.gls.services

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
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

        assertNull(first.getResource("first.txt"), "the replaced loader is still open")
        assertNotNull(currentLoader(service).getResource("second.txt"))
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
