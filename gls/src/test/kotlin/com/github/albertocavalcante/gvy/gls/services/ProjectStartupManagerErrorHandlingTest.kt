// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.build.ResolutionCodes
import com.github.albertocavalcante.gvy.build.ResolutionFailureDetail
import com.github.albertocavalcante.gvy.build.ResolutionStatus
import com.github.albertocavalcante.gvy.build.WorkspaceResolution
import com.github.albertocavalcante.gvy.build.gradle.GradleFailureAnalyzer
import com.github.albertocavalcante.gvy.build.maven.MavenFailureAnalyzer
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.project.ProjectStrategyRegistry
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.eclipse.lsp4j.MessageType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertIs

class ProjectStartupManagerErrorHandlingTest {

    @Test
    fun `should convert TOOLCHAIN_PROVISIONING_FAILED to ToolchainProvisioningError`() {
        val failedStatus = ResolutionStatus.Failed(
            code = ResolutionCodes.TOOLCHAIN_PROVISIONING_FAILED,
            message = "Cannot find Java 17",
            details = ResolutionFailureDetail.ToolchainProvisioning(
                requiredVersion = 17,
                vendor = null,
                platform = "Mac OS X 15.6 aarch64",
                suggestions = listOf("Install JDK 17"),
            ),
        )

        val manager = createManager()
        val errorDetails = manager.convertToErrorDetails(failedStatus)

        assertNotNull(errorDetails)
        assertTrue(errorDetails is ToolchainProvisioningError)

        val toolchainError = errorDetails as ToolchainProvisioningError
        assertEquals(17, toolchainError.requiredVersion)
        assertEquals("Mac OS X 15.6 aarch64", toolchainError.platform)
        assertTrue(toolchainError.suggestions.isNotEmpty())
    }

    @Test
    fun `should convert GRADLE_JDK_INCOMPATIBLE to GradleJdkIncompatibleError`() {
        val failedStatus = ResolutionStatus.Failed(
            code = ResolutionCodes.GRADLE_JDK_INCOMPATIBLE,
            message = "JDK version incompatible with Gradle",
        )

        val manager = createManager()
        val errorDetails = manager.convertToErrorDetails(failedStatus)

        assertNotNull(errorDetails)
        assertTrue(errorDetails is GradleJdkIncompatibleError)
    }

    @Test
    fun `should convert unknown error codes to GenericError`() {
        val failedStatus = ResolutionStatus.Failed(
            code = "UNKNOWN_ERROR_CODE",
            message = "Something went wrong",
        )

        val manager = createManager()
        val errorDetails = manager.convertToErrorDetails(failedStatus)

        assertNotNull(errorDetails)
        assertTrue(errorDetails is GenericError)

        val genericError = errorDetails as GenericError
        assertEquals("UNKNOWN_ERROR_CODE", genericError.errorCode)
    }

    @Test
    fun `convertToErrorDetails handles all error types correctly`() {
        val manager = createManager()

        // Test toolchain error
        val toolchainStatus = ResolutionStatus.Failed(
            code = ResolutionCodes.TOOLCHAIN_PROVISIONING_FAILED,
            message = "Cannot find Java 17",
            details = ResolutionFailureDetail.ToolchainProvisioning(
                requiredVersion = 17,
                vendor = null,
                platform = "Mac OS X",
            ),
        )
        val toolchainError = manager.convertToErrorDetails(toolchainStatus)
        assertTrue(toolchainError is ToolchainProvisioningError)
        assertEquals(17, (toolchainError as ToolchainProvisioningError).requiredVersion)

        // Test JDK incompatible error
        val jdkStatus = ResolutionStatus.Failed(
            code = ResolutionCodes.GRADLE_JDK_INCOMPATIBLE,
            message = "JDK incompatible",
        )
        val jdkError = manager.convertToErrorDetails(jdkStatus)
        assertTrue(jdkError is GradleJdkIncompatibleError)

        // Test generic error
        val genericStatus = ResolutionStatus.Failed(
            code = "UNKNOWN",
            message = "Unknown error",
        )
        val genericError = manager.convertToErrorDetails(genericStatus)
        assertTrue(genericError is GenericError)
        assertEquals("UNKNOWN", (genericError as GenericError).errorCode)
    }

    @Test
    fun `toolchain failure renders the same client payload as before`() {
        val status = GradleFailureAnalyzer().classifyException(
            RuntimeException(
                "Cannot find a Java installation on your machine (Mac OS X 15.6 aarch64) " +
                    "matching: {languageVersion=17, vendor=any vendor}",
            ),
        )

        val detail = assertIs<ResolutionFailureDetail.ToolchainProvisioning>(status.details)
        val payload = assertIs<ToolchainProvisioningError>(createManager().convertToErrorDetails(status))

        assertEquals(17, detail.requiredVersion)
        assertEquals(ToolchainProvisioningError(17, null, "Mac OS X 15.6 aarch64"), payload)
        assertEquals("TOOLCHAIN_PROVISIONING_FAILED", payload.type)
    }

    @Test
    fun `gradle jdk incompatibility renders the same client payload as before`() {
        val status = GradleFailureAnalyzer().classifyException(
            RuntimeException("Unsupported class file major version 65"),
        )

        val detail = assertIs<ResolutionFailureDetail.GradleJdkIncompatible>(status.details)
        val payload = assertIs<GradleJdkIncompatibleError>(createManager().convertToErrorDetails(status))

        assertEquals(
            GradleJdkIncompatibleError(
                gradleVersion = "unknown",
                jdkVersion = 21,
                minGradleVersion = detail.minGradleVersion ?: "unknown",
                maxJdkVersion = null,
                suggestions = detail.suggestions,
            ),
            payload,
        )
        assertEquals(21, payload.jdkVersion)
        assertTrue(payload.suggestions.isNotEmpty())
    }

    @Test
    fun `groovy jdk incompatibility renders the same client payload as before`() {
        val status = MavenFailureAnalyzer().classifyException(
            RuntimeException("Unsupported class file major version 65"),
            groovyVersion = "3.0.9",
        )

        val detail = assertIs<ResolutionFailureDetail.GroovyJdkIncompatible>(status.details)
        val payload = assertIs<GroovyJdkIncompatibleError>(createManager().convertToErrorDetails(status))

        assertEquals(
            GroovyJdkIncompatibleError(
                groovyVersion = null,
                jdkVersion = 21,
                classFileMajorVersion = 65,
                minGroovyVersion = null,
                maxJdkVersion = null,
                suggestions = detail.suggestions,
            ),
            payload,
        )
    }

    @Test
    fun `a groovy jdk detail without a known jdk falls back to the running jdk`() {
        val status = ResolutionStatus.Failed(
            code = ResolutionCodes.GROOVY_JDK_INCOMPATIBLE,
            message = "Groovy/JDK incompatibility detected.",
            details = ResolutionFailureDetail.GroovyJdkIncompatible(
                majorVersion = 99,
                jdkVersion = null,
                currentJdk = 21,
                suggestions = listOf("Upgrade Groovy"),
            ),
        )

        val payload = assertIs<GroovyJdkIncompatibleError>(createManager().convertToErrorDetails(status))

        assertEquals(21, payload.jdkVersion)
        assertEquals(99, payload.classFileMajorVersion)
        assertEquals(listOf("Upgrade Groovy"), payload.suggestions)
    }

    @Test
    fun `a groovy jdk failure without detail keeps its empty client payload`() {
        val status = ResolutionStatus.Failed(ResolutionCodes.GROOVY_JDK_INCOMPATIBLE, "incompatible")

        val payload = assertIs<GroovyJdkIncompatibleError>(createManager().convertToErrorDetails(status))

        assertEquals(0, payload.jdkVersion)
        assertEquals(0, payload.classFileMajorVersion)
        assertTrue(payload.suggestions.isEmpty())
    }

    @Test
    fun `a partial resolution is announced as a warning that names what is missing`() {
        val resolution = WorkspaceResolution(
            dependencies = listOf(java.nio.file.Path.of("a.jar")),
            sourceDirectories = emptyList(),
            status = ResolutionStatus.Warning("DEPENDENCY_RESOLUTION_FAILED", "Unresolved: org.acme:missing:1.0"),
        )

        val notice = dependencyLoadNotice(resolution, "Maven")

        assertEquals(MessageType.Warning, notice.type)
        assertTrue(notice.message.contains("org.acme:missing:1.0"), notice.message)
        assertTrue(notice.message.contains("partially"), notice.message)
    }

    @Test
    fun `a complete resolution is announced as plain information`() {
        val resolution = WorkspaceResolution(listOf(java.nio.file.Path.of("a.jar")), emptyList())

        val notice = dependencyLoadNotice(resolution, "Maven")

        assertEquals(MessageType.Info, notice.type)
        assertEquals("Dependencies loaded: 1 JARs from Maven", notice.message)
    }

    @Test
    fun `a partial resolution does not complete progress as a clean load`() {
        val partial = WorkspaceResolution(
            dependencies = listOf(java.nio.file.Path.of("a.jar")),
            sourceDirectories = emptyList(),
            status = ResolutionStatus.Warning("DEPENDENCY_RESOLUTION_FAILED", "Unresolved: org.acme:missing:1.0"),
        )
        val complete = WorkspaceResolution(listOf(java.nio.file.Path.of("a.jar")), emptyList())

        assertEquals("⚠️ Ready with partial dependencies: 1 loaded", dependencyProgressText(partial))
        assertEquals("✅ Ready: 1 dependencies loaded", dependencyProgressText(complete))
    }

    private fun createManager(): ProjectStartupManager {
        val compilationService = mockk<GroovyCompilationService>(relaxed = true)
        return ProjectStartupManager(
            compilationService = compilationService,
            availableBuildTools = emptyList(),
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            strategyRegistry = ProjectStrategyRegistry(),
        )
    }
}
