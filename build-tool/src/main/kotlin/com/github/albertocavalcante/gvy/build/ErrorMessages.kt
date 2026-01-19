package com.github.albertocavalcante.gvy.build

/**
 * Pre-built error messages with documentation links for common Gradle/toolchain issues.
 * All messages are user-friendly and include actionable suggestions.
 */
object ErrorMessages {

    // Documentation URLs
    private const val TOOLCHAIN_DOCS = "https://docs.gradle.org/current/userguide/toolchains.html"
    private const val COMPATIBILITY_DOCS = "https://docs.gradle.org/current/userguide/compatibility.html"

    /**
     * Message when Gradle cannot find the required Java toolchain.
     */
    fun toolchainNotFound(version: Int, platform: String?): String = """
        |Gradle cannot find Java $version${if (platform != null) " for $platform" else ""}.
        |
        |You may have it installed but Gradle can't find it. Options:
        |1. Set groovy.gradle.javaHome in VS Code settings
        |2. Add foojay-resolver plugin to settings.gradle for auto-download
        |3. Set -Dorg.gradle.java.installations.paths=/path/to/jdk (e.g., /usr/lib/jvm/java-$version-openjdk)
        |
        |See: $TOOLCHAIN_DOCS
        |
        |LSP running in degraded mode (syntax only, no external dependencies)
    """.trimMargin()

    /**
     * Message for JDK/Gradle version incompatibility.
     */
    fun jdkGradleIncompatible(jdkVersion: Int, gradleVersion: String, minGradle: String): String = """
        |JDK $jdkVersion requires Gradle $minGradle or higher (found $gradleVersion).
        |
        |Options:
        |1. Upgrade Gradle: ./gradlew wrapper --gradle-version=$minGradle
        |2. Set groovy.gradle.javaHome to use an older JDK
        |
        |See: $COMPATIBILITY_DOCS
    """.trimMargin()

    /**
     * Message for zero dependencies warning.
     * Returns null if project has no declared dependencies (which is valid).
     */
    fun zeroDependenciesWarning(hasDeclaredDeps: Boolean): String? = if (hasDeclaredDeps) {
        """
        |Build file declares dependencies but 0 JARs were resolved.
        |
        |This may indicate:
        |1. Network connectivity issues
        |2. Repository configuration problems
        |3. Credential/authentication issues
        |
        |Check the Groovy Language Server output for details.
        """.trimMargin()
    } else {
        null
    }

    /**
     * Message explaining degraded mode limitations.
     */
    fun degradedModeWarning(): String = """
        |LSP running in degraded mode.
        |
        |Available features:
        |  - syntax highlighting
        |  - Local symbol completion
        |  - Workspace file navigation
        |
        |Unavailable features:
        |  - external dependencies type resolution
        |  - Cross-module navigation
        |  - Full code analysis
    """.trimMargin()

    /**
     * Suggestion for version manager-specific JDK installation.
     * Note: Commands guide users to list available versions since full semantic versions are required.
     */
    fun versionManagerSuggestion(versionManager: VersionManagerType, version: Int): String = when (versionManager) {
        VersionManagerType.SDKMAN ->
            "sdk list java  # then: sdk install java <version>-tem (e.g., $version.0.14-tem)"
        VersionManagerType.MISE -> "mise install java@temurin-$version"
        VersionManagerType.ASDF ->
            "asdf list all java  # then: asdf install java temurin-<version> (e.g., temurin-$version.0.9+9)"
    }

    /**
     * Generic JDK installation suggestion with download link.
     */
    fun genericJdkInstallSuggestion(version: Int): String = """
        |Install JDK $version from:
        |  - Adoptium: https://adoptium.net/temurin/releases/?version=$version
        |  - Oracle: https://www.oracle.com/java/technologies/downloads/
    """.trimMargin()
}

/**
 * Supported version manager types for heuristic-based suggestions.
 */
enum class VersionManagerType {
    SDKMAN,
    MISE,
    ASDF,
}
