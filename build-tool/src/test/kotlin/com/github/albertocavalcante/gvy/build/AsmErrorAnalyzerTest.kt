package com.github.albertocavalcante.gvy.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AsmErrorAnalyzerTest {

    private val analyzer = AsmErrorAnalyzer()

    @Test
    fun `should detect Unsupported class file major version error`() {
        val error = RuntimeException("Unsupported class file major version 69")
        assertTrue(analyzer.isAsmError(error), "Should detect ASM error from message")
    }

    @Test
    fun `should detect ASM error case insensitively`() {
        val error = RuntimeException("unsupported CLASS FILE major VERSION 65")
        assertTrue(analyzer.isAsmError(error), "Should detect ASM error case insensitively")
    }

    @Test
    fun `should detect error in nested exception chain`() {
        val rootCause = IllegalArgumentException("Unsupported class file major version 69")
        val wrapper1 = IllegalStateException("Failed to process class", rootCause)
        val wrapper2 = RuntimeException("Build failed", wrapper1)

        assertTrue(analyzer.isAsmError(wrapper2), "Should detect ASM error in nested exception chain")
    }

    @Test
    fun `should return null for non-ASM errors`() {
        val error = RuntimeException("Some unrelated error")
        assertFalse(analyzer.isAsmError(error), "Should not detect non-ASM errors")
    }

    @Test
    fun `should extract major version and calculate JDK version`() {
        val error = RuntimeException("Unsupported class file major version 69")
        val info = analyzer.analyze(error)

        assertNotNull(info, "Should return AsmErrorInfo for ASM error")
        assertEquals(69, info!!.majorVersion, "Should extract major version 69")
        assertEquals(25, info.jdkVersion, "Should calculate JDK version 25")
    }

    @Test
    fun `should extract info from nested exception`() {
        val rootCause = IllegalArgumentException("Unsupported class file major version 65")
        val wrapper = RuntimeException("Build failed", rootCause)
        val info = analyzer.analyze(wrapper)

        assertNotNull(info, "Should return AsmErrorInfo for nested ASM error")
        assertEquals(65, info!!.majorVersion, "Should extract major version 65")
        assertEquals(21, info.jdkVersion, "Should calculate JDK version 21")
    }

    @Test
    fun `should return null for non-ASM error analysis`() {
        val error = RuntimeException("Some other error")
        val info = analyzer.analyze(error)
        assertNull(info, "Should return null for non-ASM errors")
    }

    @Test
    fun `should include current JDK in error info`() {
        // Current JDK is determined by System.getProperty("java.version")
        val error = RuntimeException("Unsupported class file major version 69")
        val info = analyzer.analyze(error)

        assertNotNull(info, "Should return AsmErrorInfo")
        assertTrue(info!!.currentJdk > 0, "Should include current JDK version")
    }

    @Test
    fun `should build suggestions for Groovy upgrade when compatible version exists`() {
        val error = RuntimeException("Unsupported class file major version 69")
        val info = analyzer.analyze(error)

        assertNotNull(info, "Should return AsmErrorInfo")
        assertTrue(info!!.suggestions.isNotEmpty(), "Should include suggestions")
        // Check that suggestions mention upgrading Groovy or downgrading JDK
        val hasSuggestion = info.suggestions.any { suggestion ->
            suggestion.contains("Groovy", ignoreCase = true) ||
                suggestion.contains("JDK", ignoreCase = true) ||
                suggestion.contains("upgrade", ignoreCase = true) ||
                suggestion.contains("downgrade", ignoreCase = true)
        }
        assertTrue(hasSuggestion, "Suggestions should mention Groovy or JDK")
    }

    @Test
    fun `should handle unknown major version gracefully`() {
        // Major version 100 doesn't map to any known JDK
        val error = RuntimeException("Unsupported class file major version 100")
        val info = analyzer.analyze(error)

        assertNotNull(info, "Should still return AsmErrorInfo even for unknown major version")
        assertEquals(100, info!!.majorVersion, "Should extract major version 100")
        assertNull(info.jdkVersion, "Unknown major version should have null JDK version")
        assertTrue(info.suggestions.isNotEmpty(), "Should still provide generic suggestions")
    }

    @Test
    fun `should handle malformed ASM error message gracefully`() {
        val error = RuntimeException("Unsupported class file major version abc")
        val info = analyzer.analyze(error)

        // Since the major version can't be parsed, this should return null
        assertNull(info, "Should return null when major version cannot be parsed")
    }

    @Test
    fun `should return null when no error message exists in chain`() {
        val error = RuntimeException(null as String?)
        val info = analyzer.analyze(error)

        assertNull(info, "Should return null when exception has no message")
    }

    @Test
    fun `should suggest current Groovy version compatibility with JDK 25`() {
        // If running on JDK 25 and encountering major version 69 error
        val error = RuntimeException("Unsupported class file major version 69")
        val info = analyzer.analyze(error)

        assertNotNull(info, "Should return AsmErrorInfo")
        // The suggestions should be relevant to the JDK version
        assertTrue(info!!.suggestions.isNotEmpty(), "Should provide actionable suggestions")
    }
}
