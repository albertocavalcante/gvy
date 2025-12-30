package com.github.albertocavalcante.groovylsp.services

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for StatusNotification and polymorphic ErrorDetails hierarchy.
 */
class StatusNotificationTest {

    private val gson: Gson = GsonBuilder().create()

    @Test
    fun `StatusNotification with error health serializes correctly`() {
        val notification = StatusNotification(
            health = Health.Error,
            quiescent = true,
            message = "Gradle/JDK incompatible",
        )

        val json = gson.toJson(notification)
        assertTrue(json.contains("\"health\":\"error\""))
        assertTrue(json.contains("\"quiescent\":true"))
        assertTrue(json.contains("Gradle/JDK incompatible"))
    }

    @Test
    fun `GradleJdkIncompatibleError contains all required fields`() {
        val error = GradleJdkIncompatibleError(
            gradleVersion = "7.0",
            jdkVersion = 21,
            minGradleVersion = "8.5",
            maxJdkVersion = "17",
            suggestions = listOf(
                "Run: ./gradlew wrapper --gradle-version=8.5",
                "Or configure groovy.gradle.java.home",
            ),
        )

        assertEquals("GRADLE_JDK_INCOMPATIBLE", error.type)
        assertEquals("7.0", error.gradleVersion)
        assertEquals(21, error.jdkVersion)
        assertEquals("8.5", error.minGradleVersion)
        assertEquals("17", error.maxJdkVersion)
        assertEquals(2, error.suggestions.size)
    }

    @Test
    fun `GradleJdkIncompatibleError serializes with type discriminator`() {
        val error = GradleJdkIncompatibleError(
            gradleVersion = "7.0",
            jdkVersion = 21,
            minGradleVersion = "8.5",
            maxJdkVersion = null,
            suggestions = listOf("Upgrade Gradle"),
        )

        val json = gson.toJson(error)

        assertTrue(json.contains("\"type\":\"GRADLE_JDK_INCOMPATIBLE\""))
        assertTrue(json.contains("\"gradleVersion\":\"7.0\""))
        assertTrue(json.contains("\"jdkVersion\":21"))
        assertTrue(json.contains("\"minGradleVersion\":\"8.5\""))
    }

    @Test
    fun `NoBuildToolError indicates syntax-only mode`() {
        val error = NoBuildToolError(
            searchedPaths = listOf("build.gradle", "build.gradle.kts", "pom.xml"),
            suggestions = listOf("Create a build.gradle file", "Or open a folder with an existing project"),
        )

        assertEquals("NO_BUILD_TOOL", error.type)
        assertEquals(3, error.searchedPaths.size)
        assertTrue(error.searchedPaths.contains("pom.xml"))
    }

    @Test
    fun `DependencyResolutionError captures build tool and cause`() {
        val error = DependencyResolutionError(
            buildTool = "Gradle",
            cause = "Could not resolve com.example:library:1.0",
            suggestions = listOf("Check your network connection", "Verify the dependency exists"),
        )

        assertEquals("DEPENDENCY_RESOLUTION_FAILED", error.type)
        assertEquals("Gradle", error.buildTool)
        assertEquals("Could not resolve com.example:library:1.0", error.cause)
    }

    @Test
    fun `JavaNotFoundError includes searched locations`() {
        val error = JavaNotFoundError(
            configuredPath = "/usr/lib/jvm/java-21",
            searchedLocations = listOf("JAVA_HOME", "groovy.java.home", "PATH"),
            suggestions = listOf("Install Java 17 or higher", "Set JAVA_HOME environment variable"),
        )

        assertEquals("JAVA_NOT_FOUND", error.type)
        assertEquals("/usr/lib/jvm/java-21", error.configuredPath)
        assertEquals(3, error.searchedLocations.size)
    }

    @Test
    fun `GenericError allows arbitrary error codes`() {
        val error = GenericError(
            errorCode = "NETWORK_TIMEOUT",
            details = mapOf("host" to "maven.central.org", "timeout" to "30s"),
            suggestions = listOf("Check your network connection"),
        )

        assertEquals("NETWORK_TIMEOUT", error.type)
        assertEquals("maven.central.org", error.details["host"])
    }

    @Test
    fun `StatusNotification with GradleJdkIncompatibleError serializes correctly`() {
        val errorDetails = GradleJdkIncompatibleError(
            gradleVersion = "7.0",
            jdkVersion = 21,
            minGradleVersion = "8.5",
            maxJdkVersion = "17",
            suggestions = listOf("Upgrade Gradle"),
        )

        val notification = StatusNotification(
            health = Health.Error,
            quiescent = true,
            message = "Gradle/JDK incompatible",
            errorCode = "GRADLE_JDK_INCOMPATIBLE",
            errorDetails = errorDetails,
        )

        val json = gson.toJson(notification)

        assertTrue(json.contains("\"errorCode\":\"GRADLE_JDK_INCOMPATIBLE\""))
        assertTrue(json.contains("\"type\":\"GRADLE_JDK_INCOMPATIBLE\""))
        assertTrue(json.contains("\"gradleVersion\":\"7.0\""))
    }

    @Test
    fun `StatusNotification defaults to no error details`() {
        val notification = StatusNotification()

        assertNull(notification.errorCode)
        assertNull(notification.errorDetails)
        assertEquals(Health.Ok, notification.health)
        assertTrue(notification.quiescent)
    }

    @Test
    fun `ErrorDetails sealed interface enforces type property`() {
        // All implementations must have a type property
        val errors: List<ErrorDetails> = listOf(
            GradleJdkIncompatibleError("7.0", 21, "8.5", null),
            NoBuildToolError(),
            DependencyResolutionError("Maven", null),
            JavaNotFoundError(null),
            GenericError("CUSTOM_ERROR"),
        )

        // Each should have a non-empty type
        errors.forEach { error ->
            assertTrue(error.type.isNotEmpty(), "Error type should not be empty")
            assertNotNull(error.suggestions, "Suggestions should not be null")
        }
    }
}
