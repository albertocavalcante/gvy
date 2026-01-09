package com.github.albertocavalcante.groovylsp.buildtool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JdkVersionMapperTest {

    @Test
    fun `should map major version 69 to JDK 25`() {
        val jdkVersion = JdkVersionMapper.toJdkVersion(69)
        assertEquals(25, jdkVersion, "Major version 69 should map to JDK 25")
    }

    @Test
    fun `should map major version 65 to JDK 21`() {
        val jdkVersion = JdkVersionMapper.toJdkVersion(65)
        assertEquals(21, jdkVersion, "Major version 65 should map to JDK 21")
    }

    @Test
    fun `should map major version 52 to JDK 8`() {
        val jdkVersion = JdkVersionMapper.toJdkVersion(52)
        assertEquals(8, jdkVersion, "Major version 52 should map to JDK 8")
    }

    @Test
    fun `should map major version 61 to JDK 17`() {
        val jdkVersion = JdkVersionMapper.toJdkVersion(61)
        assertEquals(17, jdkVersion, "Major version 61 should map to JDK 17")
    }

    @Test
    fun `should map JDK 25 to major version 69`() {
        val majorVersion = JdkVersionMapper.toMajorVersion(25)
        assertEquals(69, majorVersion, "JDK 25 should map to major version 69")
    }

    @Test
    fun `should map JDK 21 to major version 65`() {
        val majorVersion = JdkVersionMapper.toMajorVersion(21)
        assertEquals(65, majorVersion, "JDK 21 should map to major version 65")
    }

    @Test
    fun `should map JDK 8 to major version 52`() {
        val majorVersion = JdkVersionMapper.toMajorVersion(8)
        assertEquals(52, majorVersion, "JDK 8 should map to major version 52")
    }

    @Test
    fun `should parse major version from ASM error message`() {
        val errorMessage = "Unsupported class file major version 69"
        val majorVersion = JdkVersionMapper.parseMajorVersionFromError(errorMessage)
        assertEquals(69, majorVersion, "Should extract major version 69 from error message")
    }

    @Test
    fun `should parse major version from ASM error with additional context`() {
        val errorMessage = "Build failed: Unsupported class file major version 65 in file Foo.class"
        val majorVersion = JdkVersionMapper.parseMajorVersionFromError(errorMessage)
        assertEquals(65, majorVersion, "Should extract major version 65 from error message with context")
    }

    @Test
    fun `should return null for unknown major version`() {
        // Testing a major version that's way out of range (e.g., 100)
        val jdkVersion = JdkVersionMapper.toJdkVersion(100)
        assertNull(jdkVersion, "Unknown major version should return null")
    }

    @Test
    fun `should return null for malformed error messages`() {
        val errorMessage = "Some unrelated error message"
        val majorVersion = JdkVersionMapper.parseMajorVersionFromError(errorMessage)
        assertNull(majorVersion, "Should return null for error message without major version")
    }

    @Test
    fun `should handle malformed error messages gracefully`() {
        val errorMessage = "Unsupported class file major version abc"
        val majorVersion = JdkVersionMapper.parseMajorVersionFromError(errorMessage)
        assertNull(majorVersion, "Should return null for non-numeric major version")
    }

    @Test
    fun `should handle empty error messages`() {
        val errorMessage = ""
        val majorVersion = JdkVersionMapper.parseMajorVersionFromError(errorMessage)
        assertNull(majorVersion, "Should return null for empty error message")
    }

    @Test
    fun `should verify formula - majorVersion equals jdkVersion plus 44`() {
        // Testing the formula: majorVersion = jdkVersion + 44
        for (jdk in 8..25) {
            val expectedMajor = jdk + 44
            assertEquals(
                expectedMajor,
                JdkVersionMapper.toMajorVersion(jdk),
                "JDK $jdk should map to major version $expectedMajor",
            )
            assertEquals(
                jdk,
                JdkVersionMapper.toJdkVersion(expectedMajor),
                "Major version $expectedMajor should map to JDK $jdk",
            )
        }
    }
}
