package com.github.albertocavalcante.gvy.gls.e2e

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Utility for resolving paths in both Gradle and Bazel environments.
 *
 * Bazel tests run in a sandbox with runfiles at a specific location indicated
 * by RUNFILES_DIR or TEST_SRCDIR environment variables. Gradle tests run from
 * the project directory with paths resolved relative to the working directory.
 */
object BazelRunfiles {

    private val runfilesDir: Path? by lazy {
        (System.getenv("RUNFILES_DIR") ?: System.getenv("TEST_SRCDIR"))
            ?.let { Paths.get(it) }
    }

    private val workspaceName: String by lazy {
        // In bzlmod, the workspace name is "_main" by default
        // For WORKSPACE builds, it would be the workspace name from WORKSPACE file
        System.getenv("TEST_WORKSPACE") ?: "_main"
    }

    /**
     * Checks if running in a Bazel test environment.
     */
    val isRunningUnderBazel: Boolean
        get() = runfilesDir != null

    /**
     * Resolves a path that works in both Gradle and Bazel environments.
     *
     * @param relativePath Path relative to the workspace root (e.g., "gls/gls_deploy_deploy.jar")
     * @return Absolute path to the file, or null if not found
     */
    fun resolve(relativePath: String): Path? {
        val path = if (runfilesDir != null) {
            // Bazel: resolve relative to runfiles/$workspace/
            runfilesDir!!.resolve(workspaceName).resolve(relativePath)
        } else {
            // Gradle/local: resolve relative to current working directory
            Paths.get(relativePath).toAbsolutePath()
        }

        return path.takeIf { Files.exists(it) }
    }

    /**
     * Resolves a path, throwing if not found.
     *
     * @param relativePath Path relative to the workspace root
     * @param description Human-readable description for error message
     * @return Absolute path to the file
     * @throws IllegalStateException if the file doesn't exist
     */
    fun resolveOrThrow(relativePath: String, description: String = relativePath): Path = resolve(relativePath)
        ?: error(
            buildString {
                append("Unable to locate $description at path: $relativePath")
                if (runfilesDir != null) {
                    append("\n  Bazel runfiles dir: $runfilesDir")
                    append("\n  Workspace name: $workspaceName")
                    append("\n  Resolved path: ${runfilesDir!!.resolve(workspaceName).resolve(relativePath)}")
                } else {
                    append("\n  Working directory: ${Paths.get("").toAbsolutePath()}")
                    append("\n  Resolved path: ${Paths.get(relativePath).toAbsolutePath()}")
                }
            },
        )

    /**
     * Gets the absolute path string for use in subprocess commands.
     * Returns the path even if file doesn't exist (for better error messages from subprocess).
     */
    fun resolveForSubprocess(relativePath: String): String = if (runfilesDir != null) {
        runfilesDir!!.resolve(workspaceName).resolve(relativePath).toString()
    } else {
        Paths.get(relativePath).toAbsolutePath().toString()
    }
}
