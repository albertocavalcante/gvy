package com.github.albertocavalcante.groovylsp.buildtool

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Detects the correct build executable based on:
 * 1. Platform (Windows vs Unix)
 * 2. Wrapper presence (gradlew/mvnw vs gradle/mvn)
 */
object BuildExecutableResolver {

    private val logger = LoggerFactory.getLogger(BuildExecutableResolver::class.java)

    /** True if running on Windows, false otherwise. */
    val isWindows: Boolean by lazy {
        System.getProperty("os.name", "").lowercase().contains("windows")
    }

    /**
     * Resolves the Gradle executable for a workspace.
     *
     * Priority:
     * 1. Wrapper in workspace (gradlew or gradlew.bat)
     * 2. System gradle command
     */
    fun resolveGradle(workspaceRoot: Path): String = resolveExecutable(
        workspaceRoot = workspaceRoot,
        wrapperName = "gradlew",
        windowsWrapperName = "gradlew.bat",
        systemCommand = "gradle",
        useWindows = isWindows,
    )

    /**
     * Resolves the Maven executable for a workspace.
     *
     * Priority:
     * 1. Wrapper in workspace (mvnw or mvnw.cmd)
     * 2. System mvn command
     */
    fun resolveMaven(workspaceRoot: Path): String = resolveExecutable(
        workspaceRoot = workspaceRoot,
        wrapperName = "mvnw",
        windowsWrapperName = "mvnw.cmd",
        systemCommand = "mvn",
        useWindows = isWindows,
    )

    /**
     * For testing: allows overriding the Windows detection for Gradle.
     */
    internal fun resolveGradle(workspaceRoot: Path, forceWindows: Boolean): String = resolveExecutable(
        workspaceRoot = workspaceRoot,
        wrapperName = "gradlew",
        windowsWrapperName = "gradlew.bat",
        systemCommand = "gradle",
        useWindows = forceWindows,
    )

    /**
     * For testing: allows overriding the Windows detection for Maven.
     */
    internal fun resolveMaven(workspaceRoot: Path, forceWindows: Boolean): String = resolveExecutable(
        workspaceRoot = workspaceRoot,
        wrapperName = "mvnw",
        windowsWrapperName = "mvnw.cmd",
        systemCommand = "mvn",
        useWindows = forceWindows,
    )

    /**
     * Generic helper to resolve a build executable.
     * On Unix, checks executable permission and falls back to system command if not executable.
     */
    private fun resolveExecutable(
        workspaceRoot: Path,
        wrapperName: String,
        windowsWrapperName: String,
        systemCommand: String,
        useWindows: Boolean,
    ): String {
        val wrapper = if (useWindows) windowsWrapperName else wrapperName
        val wrapperPath = workspaceRoot.resolve(wrapper)

        if (!wrapperPath.exists()) return systemCommand

        // On Unix, verify executable permission
        if (!useWindows && !Files.isExecutable(wrapperPath)) {
            logger.warn(
                "Build wrapper exists but is not executable: {}. " +
                    "Fix with 'chmod +x {}' or 'git update-index --chmod=+x {}'. " +
                    "Falling back to system {}.",
                wrapperPath,
                wrapperName,
                wrapperName,
                systemCommand,
            )
            return systemCommand
        }

        return wrapperPath.toAbsolutePath().toString()
    }
}
