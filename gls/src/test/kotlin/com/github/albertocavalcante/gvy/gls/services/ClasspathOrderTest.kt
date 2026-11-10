package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals

/** The JVM resolves from the first jar that has a class: reordering jars must reach the classpath loader. */
class ClasspathOrderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `reordering jars that share a class resolves it from the new first jar`() {
        val first = jar("first.jar", "first")
        val second = jar("second.jar", "second")
        val compilation = GroovyCompilationService()
        compilation.updateWorkspaceModel(tempDir, listOf(first, second), emptyList())
        assertEquals("first", sharedResource(compilation.classpathService))

        compilation.updateWorkspaceModel(tempDir, listOf(second, first), emptyList())

        assertEquals("second", sharedResource(compilation.classpathService), "the loader kept the old order")
    }

    /** What the current loader returns for a resource both jars define. */
    private fun sharedResource(service: ClasspathService): String {
        val field = ClasspathService::class.java.getDeclaredField("currentClassLoader").apply { isAccessible = true }
        val value = field.get(service)
        val loader = ((value as? java.util.concurrent.atomic.AtomicReference<*>)?.get() ?: value) as ClassLoader
        return loader.getResource(SHARED)!!.openStream().use { String(it.readAllBytes()) }
    }

    private fun jar(name: String, content: String): Path {
        val path = tempDir.resolve(name)
        JarOutputStream(Files.newOutputStream(path)).use { out ->
            out.putNextEntry(JarEntry(SHARED))
            out.write(content.toByteArray())
            out.closeEntry()
        }
        return path
    }

    private companion object {
        const val SHARED = "com/example/Shared.txt"
    }
}
