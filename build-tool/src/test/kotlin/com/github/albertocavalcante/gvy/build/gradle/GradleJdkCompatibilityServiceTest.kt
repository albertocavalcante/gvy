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
        // The bundled mapping currently ends at 71.
        assertEquals(28, service.majorVersionToJdk(72))
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
    fun `should infer Gradle 9_1 for future JDK versions`() {
        assertEquals("9.1", service.minGradleVersionForJdk(26))
    }

    @Test
    fun `missing resources use fallback mappings`() {
        val service = GradleJdkCompatibilityService { null }

        assertEquals(21, service.majorVersionToJdk(65))
        assertNull(service.minGradleVersionForJdk(21))
        assertEquals("9.1", service.minGradleVersionForJdk(26))
    }

    @Test
    fun `malformed resources use fallback mappings`() {
        val service = GradleJdkCompatibilityService { "{ malformed".byteInputStream() }

        assertEquals(21, service.majorVersionToJdk(65))
        assertNull(service.minGradleVersionForJdk(21))
    }

    @Test
    fun `invalid resource entries discard the entire mapping`() {
        val service = GradleJdkCompatibilityService { path ->
            when (path) {
                "/class-file-versions.json" ->
                    """{"majorVersionToJdk":{"65":123,"66":"invalid"}}""".byteInputStream()
                else -> """
                    {"compatibility":[
                      {"jdk":21,"minGradle":"1.2"},
                      {"jdk":"invalid","minGradle":"8.8"}
                    ]}
                """.trimIndent().byteInputStream()
            }
        }

        assertEquals(21, service.majorVersionToJdk(65))
        assertNull(service.minGradleVersionForJdk(21))
    }

    @Test
    fun `resource metadata is ignored and null minimum Gradle is retained`() {
        val service = GradleJdkCompatibilityService { path ->
            when (path) {
                "/class-file-versions.json" ->
                    """{"majorVersionToJdk":{"65":123},"metadata":"ignored"}""".byteInputStream()
                else -> """
                    {"compatibility":[
                      {"jdk":21,"minGradle":null,"metadata":true},
                      {"jdk":22,"minGradle":"8.8"}
                    ]}
                """.trimIndent().byteInputStream()
            }
        }

        assertEquals(123, service.majorVersionToJdk(65))
        assertNull(service.minGradleVersionForJdk(21))
        assertEquals("8.8", service.minGradleVersionForJdk(22))
    }
}
