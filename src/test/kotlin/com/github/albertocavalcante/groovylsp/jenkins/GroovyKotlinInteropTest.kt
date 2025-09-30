package com.github.albertocavalcante.groovylsp.jenkins

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for Groovy-Kotlin interoperability.
 * Verifies that Kotlin can successfully call Groovy code.
 */
class GroovyKotlinInteropTest {

    @Test
    fun `test Groovy-Kotlin interop basic functionality`() {
        val interopTest = com.github.albertocavalcante.groovylsp.jenkins.JenkinsInteropTest()

        val result = interopTest.testGroovyInterop()

        assertTrue(result.success, "Groovy-Kotlin interop should succeed")
        assertNotNull(result.groovyResult, "Should get result from Groovy")
        assertEquals("Groovy-Kotlin interop is working!", result.groovyResult?.get("message"))
        assertEquals("SimpleGdslParser", result.groovyResult?.get("parser"))
    }

    @Test
    fun `test Groovy method parsing from Kotlin`() {
        val interopTest = com.github.albertocavalcante.groovylsp.jenkins.JenkinsInteropTest()

        val methods = interopTest.demonstrateTypeInterop()

        assertEquals(3, methods.size, "Should parse 3 methods")
        assertTrue(methods.contains("echo"), "Should contain 'echo' method")
        assertTrue(methods.contains("sh"), "Should contain 'sh' method")
        assertTrue(methods.contains("build"), "Should contain 'build' method")
    }

    @Test
    fun `test direct Groovy static method calls`() {
        val testResult = SimpleGdslParser.getTestResult()

        assertNotNull(testResult)
        assertEquals("Groovy-Kotlin interop is working!", testResult["message"])
        assertEquals("SimpleGdslParser", testResult["parser"])
        assertEquals("1.0", testResult["version"])
        assertTrue(testResult["timestamp"] is Long)
    }

    @Test
    fun `test Groovy GDSL parsing methods`() {
        val gdslInput = """
            contribute(context(ctype: 'hudson.model.Job')) {
                method(name: 'pipeline', type: 'Object')
                method(name: 'stage', type: 'void')
                method(name: 'checkout', type: 'hudson.scm.SCM')
            }
        """.trimIndent()

        val methodNames = SimpleGdslParser.parseMethodNames(gdslInput)
        val isValid = SimpleGdslParser.isValidGdsl(gdslInput)

        assertEquals(3, methodNames.size)
        assertTrue(methodNames.contains("pipeline"))
        assertTrue(methodNames.contains("stage"))
        assertTrue(methodNames.contains("checkout"))
        assertTrue(isValid)
    }

    @Test
    fun `test empty and invalid GDSL input`() {
        assertEquals(emptyList<String>(), SimpleGdslParser.parseMethodNames(""))
        assertEquals(emptyList<String>(), SimpleGdslParser.parseMethodNames(null))

        assertFalse(SimpleGdslParser.isValidGdsl(""))
        assertFalse(SimpleGdslParser.isValidGdsl(null))
        assertFalse(SimpleGdslParser.isValidGdsl("invalid content"))
    }
}
