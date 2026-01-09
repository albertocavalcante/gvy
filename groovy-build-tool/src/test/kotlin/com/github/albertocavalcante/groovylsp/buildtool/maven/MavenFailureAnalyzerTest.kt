package com.github.albertocavalcante.groovylsp.buildtool.maven

import com.github.albertocavalcante.groovylsp.buildtool.ResolutionCodes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MavenFailureAnalyzerTest {

    private val analyzer = MavenFailureAnalyzer()

    @Test
    fun `should detect ASM version errors`() {
        val exception = RuntimeException("Unsupported class file major version 65")
        assertTrue(analyzer.isAsmVersionError(exception))
    }

    @Test
    fun `should detect ASM version errors in exception chain`() {
        val rootCause = IllegalArgumentException("Unsupported class file major version 61")
        val wrappedException = RuntimeException("Failed to resolve", rootCause)
        assertTrue(analyzer.isAsmVersionError(wrappedException))
    }

    @Test
    fun `should not detect ASM version errors when none present`() {
        val exception = RuntimeException("Some other error")
        assertFalse(analyzer.isAsmVersionError(exception))
    }

    @Test
    fun `should detect POM parsing errors`() {
        val exception = RuntimeException("ModelBuildingException: Failed to parse POM")
        assertTrue(analyzer.isPomParsingError(exception))
    }

    @Test
    fun `should detect POM parsing errors with different message`() {
        val exception = RuntimeException("Failed to parse POM at /path/to/pom.xml")
        assertTrue(analyzer.isPomParsingError(exception))
    }

    @Test
    fun `should detect connectivity errors`() {
        val exception = RuntimeException("Could not transfer artifact org.example:library:jar:1.0: Connection refused")
        assertTrue(analyzer.isConnectivityError(exception))
    }

    @Test
    fun `should detect connectivity errors with timeout`() {
        val exception = RuntimeException("Could not GET https://repo.maven.apache.org/maven2/: Connection timed out")
        assertTrue(analyzer.isConnectivityError(exception))
    }

    @Test
    fun `should detect dependency resolution errors`() {
        val exception = RuntimeException("Could not resolve dependencies for project com.example:app:1.0")
        assertTrue(analyzer.isDependencyResolutionError(exception))
    }

    @Test
    fun `should detect dependency resolution errors with artifact not found`() {
        val exception = RuntimeException("Could not find artifact org.example:missing:jar:1.0")
        assertTrue(analyzer.isDependencyResolutionError(exception))
    }

    @Test
    fun `should classify ASM error with GROOVY_JDK_INCOMPATIBLE code`() {
        val exception = RuntimeException("Unsupported class file major version 65")
        val result = analyzer.classifyException(exception, groovyVersion = "3.0.9")

        assertEquals(ResolutionCodes.GROOVY_JDK_INCOMPATIBLE, result.code)
        assertTrue(result.message.contains("JDK"))
        assertTrue(result.message.contains("Groovy"))
    }

    @Test
    fun `should classify ASM error without Groovy version`() {
        val exception = RuntimeException("Unsupported class file major version 65")
        val result = analyzer.classifyException(exception, groovyVersion = null)

        assertEquals(ResolutionCodes.GROOVY_JDK_INCOMPATIBLE, result.code)
    }

    @Test
    fun `should classify POM parsing error`() {
        val exception = RuntimeException("ModelBuildingException: Invalid POM structure")
        val result = analyzer.classifyException(exception, groovyVersion = null)

        assertEquals(ResolutionCodes.POM_PARSING_FAILED, result.code)
        assertTrue(result.message.contains("POM"))
    }

    @Test
    fun `should classify connectivity error`() {
        val exception = RuntimeException("Could not transfer artifact: Connection refused")
        val result = analyzer.classifyException(exception, groovyVersion = null)

        assertEquals(ResolutionCodes.CONNECTIVITY_ERROR, result.code)
        assertTrue(result.message.contains("connectivity") || result.message.contains("network"))
    }

    @Test
    fun `should classify dependency resolution error`() {
        val exception = RuntimeException("Could not resolve dependencies")
        val result = analyzer.classifyException(exception, groovyVersion = null)

        assertEquals(ResolutionCodes.DEPENDENCY_RESOLUTION_FAILED, result.code)
    }

    @Test
    fun `should walk exception chain for classification`() {
        val rootCause = IllegalStateException("Unsupported class file major version 65")
        val middleException = IllegalArgumentException("Failed during processing", rootCause)
        val topException = RuntimeException("Build failed", middleException)

        val result = analyzer.classifyException(topException, groovyVersion = "3.0.9")

        assertEquals(ResolutionCodes.GROOVY_JDK_INCOMPATIBLE, result.code)
    }

    @Test
    fun `should prioritize ASM errors over other errors`() {
        // If multiple error types are present, ASM errors should be detected first
        val exception = RuntimeException(
            "Could not resolve dependencies: Unsupported class file major version 65",
        )
        val result = analyzer.classifyException(exception, groovyVersion = "3.0.9")

        assertEquals(ResolutionCodes.GROOVY_JDK_INCOMPATIBLE, result.code)
    }

    @Test
    fun `should classify generic error when no specific pattern matches`() {
        val exception = RuntimeException("Some unknown build error")
        val result = analyzer.classifyException(exception, groovyVersion = null)

        assertEquals(ResolutionCodes.DEPENDENCY_RESOLUTION_FAILED, result.code)
    }
}
