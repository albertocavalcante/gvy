package com.github.albertocavalcante.groovylsp.buildtool

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ErrorMessagesTest {

    @Test
    fun `toolchainNotFound includes version in message`() {
        val message = ErrorMessages.toolchainNotFound(17, "Mac OS X aarch64")
        assertContains(message, "Java 17")
        assertContains(message, "Mac OS X aarch64")
    }

    @Test
    fun `toolchainNotFound works without platform`() {
        val message = ErrorMessages.toolchainNotFound(21, null)
        assertContains(message, "Java 21")
    }

    @Test
    fun `toolchainNotFound includes documentation link`() {
        val message = ErrorMessages.toolchainNotFound(17, null)
        assertContains(message, "https://docs.gradle.org/current/userguide/toolchains.html")
    }

    @Test
    fun `toolchainNotFound includes actionable suggestions`() {
        val message = ErrorMessages.toolchainNotFound(17, null)
        assertContains(message, "groovy.gradle.javaHome")
        assertContains(message, "foojay-resolver")
        assertContains(message, "org.gradle.java.installations.paths")
    }

    @Test
    fun `jdkGradleIncompatible includes versions`() {
        val message = ErrorMessages.jdkGradleIncompatible(21, "7.6", "8.5")
        assertContains(message, "JDK 21")
        assertContains(message, "7.6")
        assertContains(message, "8.5")
    }

    @Test
    fun `jdkGradleIncompatible includes compatibility docs`() {
        val message = ErrorMessages.jdkGradleIncompatible(21, "7.6", "8.5")
        assertContains(message, "https://docs.gradle.org/current/userguide/compatibility.html")
    }

    @Test
    fun `zeroDependenciesWarning returns message when deps declared`() {
        val message = ErrorMessages.zeroDependenciesWarning(hasDeclaredDeps = true)
        assertNotNull(message)
        assertContains(message, "0 JARs")
    }

    @Test
    fun `zeroDependenciesWarning returns null when no deps declared`() {
        val message = ErrorMessages.zeroDependenciesWarning(hasDeclaredDeps = false)
        assertNull(message)
    }

    @Test
    fun `degradedModeWarning explains limitations`() {
        val message = ErrorMessages.degradedModeWarning()
        assertContains(message, "syntax")
        assertContains(message, "external dependencies")
    }

    @Test
    fun `versionManagerSuggestion returns sdkman for SDKMAN`() {
        val message = ErrorMessages.versionManagerSuggestion(VersionManagerType.SDKMAN, 17)
        assertContains(message, "sdk install")
    }

    @Test
    fun `versionManagerSuggestion returns mise for MISE`() {
        val message = ErrorMessages.versionManagerSuggestion(VersionManagerType.MISE, 17)
        assertContains(message, "mise install")
    }

    @Test
    fun `versionManagerSuggestion returns asdf for ASDF`() {
        val message = ErrorMessages.versionManagerSuggestion(VersionManagerType.ASDF, 17)
        assertContains(message, "asdf install")
    }

    @Test
    fun `genericJdkInstallSuggestion includes version and download links`() {
        val message = ErrorMessages.genericJdkInstallSuggestion(17)
        assertContains(message, "17")
        assertContains(message, "https://adoptium.net/temurin/releases/?version=17")
        assertContains(message, "https://www.oracle.com/java/technologies/downloads/")
    }
}
