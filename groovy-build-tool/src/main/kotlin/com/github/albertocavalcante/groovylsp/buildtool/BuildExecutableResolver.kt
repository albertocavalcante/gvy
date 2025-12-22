package com.github.albertocavalcante.groovylsp.buildtool

import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Detects the correct build executable based on:
 * 1. Platform (Windows vs Unix)
 * 2. Wrapper presence (gradlew/mvnw vs gradle/mvn)
 */
object BuildExecutableResolver {

    private val isWindows: Boolean by lazy {
        System.getProperty("os.name", "").lowercase().contains("windows")
    }

    /**
     * Resolves the Gradle executable for a workspace.
     *
     * Priority:
     * 1. Wrapper in workspace (gradlew or gradlew.bat)
     * 2. System gradle command
     */
    fun resolveGradle(workspaceRoot: Path): String {
        val wrapper = if (isWindows) "gradlew.bat" else "gradlew"
        val wrapperPath = workspaceRoot.resolve(wrapper)

        return if (wrapperPath.exists()) {
            if (isWindows) wrapper else "./$wrapper"
        } else {
            "gradle"
        }
    }

    /**
     * Resolves the Maven executable for a workspace.
     *
     * Priority:
     * 1. Wrapper in workspace (mvnw or mvnw.cmd)
     * 2. System mvn command
     */
    fun resolveMaven(workspaceRoot: Path): String {
        val wrapper = if (isWindows) "mvnw.cmd" else "mvnw"
        val wrapperPath = workspaceRoot.resolve(wrapper)

        return if (wrapperPath.exists()) {
            if (isWindows) wrapper else "./$wrapper"
        } else {
            "mvn"
        }
    }

    /**
     * For testing: allows overriding the Windows detection.
     */
    internal fun resolveGradle(workspaceRoot: Path, forceWindows: Boolean): String {
        val wrapper = if (forceWindows) "gradlew.bat" else "gradlew"
        val wrapperPath = workspaceRoot.resolve(wrapper)

        return if (wrapperPath.exists()) {
            if (forceWindows) wrapper else "./$wrapper"
        } else {
            "gradle"
        }
    }

    /**
     * For testing: allows overriding the Windows detection.
     */
    internal fun resolveMaven(workspaceRoot: Path, forceWindows: Boolean): String {
        val wrapper = if (forceWindows) "mvnw.cmd" else "mvnw"
        val wrapperPath = workspaceRoot.resolve(wrapper)

        return if (wrapperPath.exists()) {
            if (forceWindows) wrapper else "./$wrapper"
        } else {
            "mvn"
        }
    }
}
