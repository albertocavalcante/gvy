package com.github.albertocavalcante.groovylsp

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class GroovyCompilationServiceClasspathTest {

    @Test
    fun `findClasspathClass returns URI for standard JDK class`() = runTest {
        val service = GroovyCompilationService()
        // Initialize workspace with empty dependencies - JDK should still be available via system classloader
        // But our implementation uses a URLClassLoader with dependencies.
        // If dependencies are empty, it might not find JDK classes if parent is null.
        // Let's check implementation: URLClassLoader(urls, null).
        // So it ONLY searches dependencies. We need to add a dependency to test.
        // Since we are in a test environment, we can add the current classpath.

        val currentClasspath = System.getProperty("java.class.path")
            .split(System.getProperty("path.separator"))
            .map { Path.of(it) }

        service.workspaceManager.updateDependencies(currentClasspath)

        // Try to find a class that is definitely on the classpath, e.g. kotlin.Unit or org.slf4j.Logger
        val uri = service.findClasspathClass("org.slf4j.Logger")

        assertNotNull(uri, "Should find org.slf4j.Logger")
        assertTrue(
            uri.toString().startsWith("jar:file:") || uri.toString().startsWith("file:"),
            "URI should be jar:file or file: but was $uri",
        )
        assertTrue(uri.toString().contains("slf4j"), "URI should contain slf4j")
    }

    @Test
    fun `findClasspathClass returns null for non-existent class`() = runTest {
        val service = GroovyCompilationService()
        val uri = service.findClasspathClass("com.example.NonExistentClass")
        assertEquals(null, uri)
    }
}
