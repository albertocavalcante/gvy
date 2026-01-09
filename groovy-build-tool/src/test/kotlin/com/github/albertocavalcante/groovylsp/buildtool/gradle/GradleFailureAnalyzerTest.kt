package com.github.albertocavalcante.groovylsp.buildtool.gradle

import com.github.albertocavalcante.groovylsp.buildtool.ResolutionCodes
import org.gradle.tooling.BuildException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `should detect toolchain provisioning error from direct message`() {
        val error = RuntimeException(
            "Cannot find a Java installation on your machine (Mac OS X 15.6 aarch64) matching: " +
                "{languageVersion=17, vendor=any vendor, implementation=vendor-specific}. " +
                "Toolchain download repositories have not been configured.",
        )
        assertTrue(
            analyzer.isToolchainProvisioningError(error),
            "Should detect toolchain provisioning error from message",
        )
    }

    @Test
    fun `should detect toolchain provisioning error from nested cause`() {
        // Simulating real stack trace: BuildException -> LocationAwareException -> ... -> ToolchainProvisioningException
        val rootCause = RuntimeException(
            "Cannot find a Java installation on your machine matching: {languageVersion=17}",
        )
        val wrappingException = IllegalStateException(
            "Failed to query the value of task ':compileGroovy' property 'javaLauncher'.",
            rootCause,
        )
        val buildException = RuntimeException("A problem occurred configuring root project", wrappingException)

        assertTrue(
            analyzer.isToolchainProvisioningError(buildException),
            "Should detect toolchain provisioning error from nested cause",
        )
    }

    @Test
    fun `should detect 'Toolchain download repositories have not been configured' message`() {
        val error = RuntimeException("Toolchain download repositories have not been configured")
        assertTrue(analyzer.isToolchainProvisioningError(error))
    }

    @Test
    fun `should NOT classify toolchain error as init script error`() {
        val error = RuntimeException(
            "Cannot find a Java installation on your machine matching: {languageVersion=17}. " +
                "Toolchain download repositories have not been configured.",
        )
        assertFalse(
            analyzer.isInitScriptError(error),
            "Toolchain error should NOT be treated as init script error",
        )
    }

    @Test
    fun `should NOT classify toolchain error as transient`() {
        val error = RuntimeException(
            "Cannot find a Java installation on your machine matching: {languageVersion=17}",
        )
        assertFalse(
            analyzer.isTransient(error),
            "Toolchain error should NOT be treated as transient (no point retrying)",
        )
    }

    // Test extractToolchainErrorInfo
    @Test
    fun `extractToolchainErrorInfo parses version and platform from nested exception`() {
        val error = RuntimeException(
            "Cannot find a Java installation on your machine (Mac OS X 15.6 aarch64) matching: " +
                "{languageVersion=17, vendor=any vendor, implementation=vendor-specific}",
        )
        val info = analyzer.extractToolchainErrorInfo(error)
        assertNotNull(info)
        assertEquals(17, info!!.requiredVersion)
        assertEquals("Mac OS X 15.6 aarch64", info.platform)
        assertNull(info.vendor) // "any vendor" should normalize to null
        assertTrue(info.suggestions.isNotEmpty())
    }

    @Test
    fun `extractToolchainErrorInfo returns null for non-toolchain errors`() {
        val error = RuntimeException("Some other error")
        val info = analyzer.extractToolchainErrorInfo(error)
        assertNull(info)
    }

    @Test
    fun `extractToolchainErrorInfo walks exception chain`() {
        val rootCause = RuntimeException("Cannot find a Java installation matching: {languageVersion=21}")
        val wrapper = IllegalStateException("Failed to query", rootCause)
        val info = analyzer.extractToolchainErrorInfo(wrapper)
        assertNotNull(info)
        assertEquals(21, info!!.requiredVersion)
    }

    // Test classifyException
    @Test
    fun `classifyException returns TOOLCHAIN_PROVISIONING_FAILED for toolchain errors`() {
        val error = RuntimeException("Cannot find a Java installation matching: {languageVersion=17}")
        val status = analyzer.classifyException(error)
        assertEquals(ResolutionCodes.TOOLCHAIN_PROVISIONING_FAILED, status.code)
        assertTrue(status.message.contains("Java 17"))
        assertNotNull(status.details, "Should contain structured details")
        assertTrue(status.details is GradleFailureAnalyzer.ToolchainErrorInfo)
    }

    @Test
    fun `classifyException returns GRADLE_JDK_INCOMPATIBLE for JDK mismatch`() {
        val error = RuntimeException("Unsupported class file major version 65")
        val status = analyzer.classifyException(error)
        assertEquals(ResolutionCodes.GRADLE_JDK_INCOMPATIBLE, status.code)
    }

    @Test
    fun `classifyException returns INIT_SCRIPT_ERROR for init script failures`() {
        val error = RuntimeException("Could not open cp_init generic class cache")
        val status = analyzer.classifyException(error)
        assertEquals(ResolutionCodes.INIT_SCRIPT_ERROR, status.code)
    }

    @Test
    fun `classifyException returns DEPENDENCY_RESOLUTION_FAILED for unknown errors`() {
        val error = RuntimeException("Something went wrong")
        val status = analyzer.classifyException(error)
        assertEquals(ResolutionCodes.DEPENDENCY_RESOLUTION_FAILED, status.code)
    }

    // Test fixtures from gradle-errors/*.txt files
    @Test
    fun `should detect connection refused error from fixture`() {
        val errorTrace = loadFixture("connection-refused-error.txt")
        val error = RuntimeException(errorTrace)
        assertTrue(analyzer.isTransient(error), "Connection refused should be classified as transient")
    }

    @Test
    fun `should detect init script error from fixture`() {
        val errorTrace = loadFixture("init-script-error.txt")
        val error = RuntimeException(errorTrace)
        assertTrue(analyzer.isInitScriptError(error), "Init script error should be detected")
        assertEquals(ResolutionCodes.INIT_SCRIPT_ERROR, analyzer.classifyException(error).code)
    }

    @Test
    fun `should detect JDK mismatch from fixture`() {
        val errorTrace = loadFixture("jdk-mismatch-error.txt")
        val error = RuntimeException(errorTrace)
        assertTrue(analyzer.isJdkMismatch(error), "JDK mismatch should be detected")
        val status = analyzer.classifyException(error)
        assertEquals(ResolutionCodes.GRADLE_JDK_INCOMPATIBLE, status.code)
        assertNotNull(status.details)
        assertTrue(status.details is GradleFailureAnalyzer.GradleJdkIncompatibleInfo)
        val info = status.details as GradleFailureAnalyzer.GradleJdkIncompatibleInfo
        assertEquals(21, info.jdkVersion, "Should detect JDK 21 from class file major version 65")
    }

    @Test
    fun `should detect lock timeout error from fixture`() {
        val errorTrace = loadFixture("lock-timeout-error.txt")
        val error = RuntimeException(errorTrace)
        assertTrue(analyzer.isTransient(error), "Lock timeout should be classified as transient")
    }

    @Test
    fun `should detect toolchain provisioning error from fixture`() {
        val errorTrace = loadFixture("toolchain-provisioning-error.txt")
        val error = RuntimeException(errorTrace)
        assertTrue(analyzer.isToolchainProvisioningError(error), "Toolchain provisioning error should be detected")
        val status = analyzer.classifyException(error)
        assertEquals(ResolutionCodes.TOOLCHAIN_PROVISIONING_FAILED, status.code)
        assertNotNull(status.details)
        assertTrue(status.details is GradleFailureAnalyzer.ToolchainErrorInfo)
        val info = status.details as GradleFailureAnalyzer.ToolchainErrorInfo
        assertEquals(17, info.requiredVersion, "Should detect required Java 17")
        assertEquals("Mac OS X 15.6 aarch64", info.platform)
    }

    private fun loadFixture(filename: String): String {
        val resource = javaClass.classLoader.getResourceAsStream("gradle-errors/$filename")
            ?: error("Fixture file not found: $filename")
        return resource.bufferedReader().use { it.readText() }
    }
}
