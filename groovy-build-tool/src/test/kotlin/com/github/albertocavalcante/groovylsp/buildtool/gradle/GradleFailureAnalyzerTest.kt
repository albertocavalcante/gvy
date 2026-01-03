package com.github.albertocavalcante.groovylsp.buildtool.gradle

import org.gradle.tooling.BuildException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GradleFailureAnalyzerTest {

    private val analyzer = GradleFailureAnalyzer()

    @Test
    fun `should detect JDK mismatch`() {
        val error = BuildException(
            "Build failed",
            IllegalStateException("Unsupported class file major version 65"),
        )
        assertTrue(analyzer.isJdkMismatch(error), "Should detect JDK mismatch from cause")
    }

    @Test
    fun `should detect init script errors`() {
        val error = BuildException("Could not run build action using init script", null)
        assertTrue(analyzer.isInitScriptError(error), "Should detect init script error")

        // Case insensitive
        val error2 = RuntimeException("Problem with INIT.D script")
        assertTrue(analyzer.isInitScriptError(error2))
    }

    @Test
    fun `should NOT classify JDK mismatch as init script error`() {
        // This is the CRITICAL fix logic
        val error = BuildException("Unsupported class file major version 65", null)
        assertFalse(
            analyzer.isInitScriptError(error),
            "JDK mismatch should NOT be treated as init script error, to prevent useless retries",
        )
    }

    @Test
    fun `should detect transient errors`() {
        val error = RuntimeException("Timeout waiting for lock")
        assertTrue(analyzer.isTransient(error))
    }
}
