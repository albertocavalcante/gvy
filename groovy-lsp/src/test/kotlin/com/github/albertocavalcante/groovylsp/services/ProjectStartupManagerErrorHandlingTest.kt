package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.buildtool.ResolutionCodes
import com.github.albertocavalcante.groovylsp.buildtool.ResolutionStatus
import com.github.albertocavalcante.groovylsp.buildtool.WorkspaceResolution
import com.github.albertocavalcante.groovylsp.buildtool.gradle.GradleFailureAnalyzer
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import com.github.albertocavalcante.groovylsp.gradle.DependencyManager
import com.github.albertocavalcante.groovylsp.project.ProjectStrategyRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.eclipse.lsp4j.InitializeParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectStartupManagerErrorHandlingTest {

    @Test
    fun `should convert TOOLCHAIN_PROVISIONING_FAILED to ToolchainProvisioningError`() {
        val failedStatus = ResolutionStatus.Failed(
            code = ResolutionCodes.TOOLCHAIN_PROVISIONING_FAILED,
            message = "Cannot find Java 17",
            details = GradleFailureAnalyzer.ToolchainErrorInfo(
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
            details = GradleFailureAnalyzer.ToolchainErrorInfo(
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
