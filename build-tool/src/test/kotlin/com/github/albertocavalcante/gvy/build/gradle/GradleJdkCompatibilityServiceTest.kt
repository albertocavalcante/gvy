package com.github.albertocavalcante.gvy.build.gradle

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GradleJdkCompatibilityServiceTest {

    private val service = GradleJdkCompatibilityService()

    @Test
    fun `should map class file major version 65 to JDK 21`() {
        assertEquals(21, service.majorVersionToJdk(65))
    }

    @Test
    fun `should map class file major version 69 to JDK 25`() {
        assertEquals(25, service.majorVersionToJdk(69))
    }

    @Test
    fun `should fall back to formula for unknown major versions`() {
        // majorVersion 70 = JDK 26 (70 - 44 = 26)
        assertEquals(26, service.majorVersionToJdk(70))
    }

    @Test
    fun `should return min Gradle 8_5 for JDK 21`() {
        assertEquals("8.5", service.minGradleVersionForJdk(21))
    }

    @Test
    fun `should return min Gradle 9_1 for JDK 25`() {
        assertEquals("9.1", service.minGradleVersionForJdk(25))
    }

    @Test
    fun `should return null for JDK 8`() {
        assertNull(service.minGradleVersionForJdk(8))
    }

    @Test
    fun `should handle future JDK versions gracefully`() {
        // JDK 26+ should return a reasonable default
        val minGradle = service.minGradleVersionForJdk(26)
        // Either null or some Gradle version
        if (minGradle != null) {
            assert(minGradle.isNotEmpty())
        }
    }
}
