package com.github.albertocavalcante.groovylsp.buildtool

/**
 * Strategy for how groovy-lsp resolves Gradle project dependencies.
 *
 * This allows users to control whether to use the native Gradle Tooling API
 * or the Build Server Protocol (BSP) when both are available.
 *
 * @see <a href="https://devblogs.microsoft.com/java/new-build-server-for-gradle/">Microsoft Build Server for Gradle</a>
 */
enum class GradleBuildStrategy {
    /**
     * Auto-detect: Use BSP if `.bsp/gradle.json` exists, otherwise use native Gradle Tooling API.
     * This is the default behavior and works seamlessly with the Gradle Build Server when installed.
     */
    AUTO,

    /**
     * Prefer BSP for Gradle projects. If a BSP connection file exists (including gradle.json),
     * use it instead of the native Gradle Tooling API.
     *
     * Useful when:
     * - You have the Gradle Build Server installed and want consistent BSP behavior
     * - You need features specific to the BSP server (e.g., better annotation processing support)
     */
    BSP_PREFERRED,

    /**
     * Always use native Gradle Tooling API, even if a BSP connection is available.
     * Skips BSP detection entirely for Gradle projects.
     *
     * Useful when:
     * - You want faster cold starts (no external server dependency)
     * - The BSP server is unstable or not working correctly
     * - You prefer the direct Gradle integration
     */
    NATIVE_ONLY,
    ;

    companion object {
        /**
         * Parses a string value to [GradleBuildStrategy].
         * Accepts: "auto", "bsp", "bsp_preferred", "native", "native_only" (case-insensitive)
         *
         * @return The parsed strategy, or [AUTO] if the value is null or unrecognized
         */
        fun fromString(value: String?): GradleBuildStrategy = when (value?.lowercase()?.trim()) {
            "auto" -> AUTO
            "bsp", "bsp_preferred", "bsp-preferred" -> BSP_PREFERRED
            "native", "native_only", "native-only" -> NATIVE_ONLY
            null -> AUTO
            else -> AUTO
        }
    }
}
