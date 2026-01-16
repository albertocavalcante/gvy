package com.github.albertocavalcante.groovylsp.services

import com.github.albertocavalcante.groovylsp.buildtool.ResolutionCodes
import com.github.albertocavalcante.groovylsp.buildtool.ResolutionStatus
import com.github.albertocavalcante.groovylsp.buildtool.jdk.ProjectJdkValidator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.services.LanguageClient
import java.nio.file.Path

/**
 * Result of the pre-flight JDK compatibility check.
 */
internal enum class PreflightResult {
    /** Continue with dependency resolution. */
    Continue,

    /** Abort dependency resolution due to fatal JDK incompatibility. */
    Abort,
}

/**
 * Handles JDK validation for project startup.
 *
 * This class performs pre-flight JDK compatibility checks before dependency resolution,
 * detecting version mismatches early to provide clear feedback to users.
 */
internal object JdkValidator {
    private val logger = KotlinLogging.logger {}

    /**
     * Performs a pre-flight JDK compatibility check before dependency resolution.
     *
     * This is the "fail-fast" mechanism that detects JDK version mismatches early,
     * before any compilation or dependency resolution is attempted.
     *
     * @param workspaceRoot The workspace root directory to validate.
     * @param client The LSP client for sending notifications.
     * @param onStatusUpdate Callback for status updates.
     * @return PreflightResult indicating whether to continue or abort.
     */
    fun performPreflightCheck(
        workspaceRoot: Path,
        client: LanguageClient?,
        onStatusUpdate: StatusUpdateCallback,
    ): PreflightResult {
        val validator = ProjectJdkValidator()
        val result = validator.validate(workspaceRoot)

        return when (result) {
            is ProjectJdkValidator.ValidationResult.IncompatibleOlder -> {
                handleIncompatibleOlder(result, client, onStatusUpdate)
            }

            is ProjectJdkValidator.ValidationResult.PotentiallyIncompatibleNewer -> {
                handlePotentiallyIncompatibleNewer(result, onStatusUpdate)
            }

            is ProjectJdkValidator.ValidationResult.Compatible -> {
                logger.debug {
                    "JDK validation passed: running JDK ${result.runningJdk}, required ${result.requiredJdk}"
                }
                PreflightResult.Continue
            }

            ProjectJdkValidator.ValidationResult.NoRequirement -> {
                logger.debug { "No JDK requirement configured in project, skipping validation" }
                PreflightResult.Continue
            }
        }
    }

    private fun handleIncompatibleOlder(
        result: ProjectJdkValidator.ValidationResult.IncompatibleOlder,
        client: LanguageClient?,
        onStatusUpdate: StatusUpdateCallback,
    ): PreflightResult {
        // Fatal: Running JDK is older than required
        logger.error {
            "JDK version mismatch: running JDK ${result.runningJdk} but project requires JDK ${result.requiredJdk}"
        }

        val errorDetails = ProjectJdkIncompatibleError(
            runningJdkVersion = result.runningJdk,
            requiredJdkVersion = result.requiredJdk,
            configurationSource = result.source.displayName,
            suggestions = result.suggestions,
        )

        // Send error status
        onStatusUpdate(
            Health.Error,
            true,
            "Project requires JDK ${result.requiredJdk}, but LSP is running JDK ${result.runningJdk}",
            null,
            null,
            ResolutionCodes.PROJECT_JDK_INCOMPATIBLE,
            errorDetails,
        )

        // Show user-visible message
        client?.showMessage(
            MessageParams().apply {
                type = MessageType.Error
                message = "Project requires JDK ${result.requiredJdk} (from ${result.source.displayName}), " +
                    "but LSP is running JDK ${result.runningJdk}. Configure groovy.java.home to fix."
            },
        )

        return PreflightResult.Abort
    }

    private fun handlePotentiallyIncompatibleNewer(
        result: ProjectJdkValidator.ValidationResult.PotentiallyIncompatibleNewer,
        onStatusUpdate: StatusUpdateCallback,
    ): PreflightResult {
        // Warning: Running JDK is significantly newer than target
        logger.warn {
            "JDK version warning: running JDK ${result.runningJdk} but project targets JDK ${result.targetJdk}"
        }

        // Create structured warning details for client-side handling
        val warningDetails = ProjectJdkNewerWarning(
            runningJdkVersion = result.runningJdk,
            targetJdkVersion = result.targetJdk,
            configurationSource = result.source.displayName,
        )

        // Send warning status via groovy/status notification
        // This allows the client to show actionable buttons
        onStatusUpdate(
            Health.Warning,
            false, // Not quiescent yet - we'll continue with resolution
            "LSP JDK ${result.runningJdk} is newer than project target ${result.targetJdk}. " +
                "You may see 'Unsupported class file major version' errors.",
            null,
            null,
            "PROJECT_JDK_NEWER_WARNING",
            warningDetails,
        )

        return PreflightResult.Continue
    }

    /**
     * Converts a ResolutionStatus.Failed to ErrorDetails for JDK-related errors.
     *
     * This is used to convert build tool error statuses into structured error details
     * that can be sent to the client.
     *
     * @param status The failed resolution status.
     * @return ErrorDetails if the status is JDK-related, null otherwise.
     */
    fun convertToErrorDetails(status: ResolutionStatus.Failed): ErrorDetails? = when (status.code) {
        ResolutionCodes.PROJECT_JDK_INCOMPATIBLE -> {
            // This case is already handled by performPreflightCheck
            // but included here for completeness
            null
        }
        else -> null
    }
}
