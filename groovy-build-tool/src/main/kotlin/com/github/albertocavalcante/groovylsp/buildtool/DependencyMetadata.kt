package com.github.albertocavalcante.groovylsp.buildtool

/**
 * Rich dependency metadata for UI display (e.g., VS Code dependency tree view).
 *
 * This provides more information than [WorkspaceResolution] which only contains
 * JAR paths. This metadata includes Maven coordinates, scope, and transitivity.
 */
data class DependencyMetadata(
    /**
     * Dependency name, preferably in Maven coordinate format (groupId:artifactId).
     * Falls back to artifact name if coordinates are unavailable.
     * Examples: "org.apache.commons:commons-lang3", "commons-lang3"
     */
    val name: String,

    /**
     * Version string (e.g., "3.12.0", "2.0.0-SNAPSHOT").
     * May be "unknown" if version cannot be determined.
     */
    val version: String,

    /**
     * Normalized dependency scope. One of:
     * - "compile" - Available at compile time and runtime
     * - "runtime" - Only needed at runtime
     * - "test" - Only for test compilation and execution
     * - "provided" - Expected to be provided by the runtime environment
     */
    val scope: String,

    /**
     * File URI to the dependency JAR (e.g., "file:///path/to/artifact.jar").
     */
    val path: String,

    /**
     * Whether this is a transitive dependency (not declared directly in build file).
     * - false = Direct dependency declared in build.gradle/pom.xml
     * - true = Pulled in transitively by another dependency
     */
    val isTransitive: Boolean,
) {
    companion object {
        /** Standard scope values */
        const val SCOPE_COMPILE = "compile"
        const val SCOPE_RUNTIME = "runtime"
        const val SCOPE_TEST = "test"
        const val SCOPE_PROVIDED = "provided"

        /**
         * Normalizes various build-tool-specific scope names to standard values.
         *
         * Gradle scopes: implementation, api, compileOnly, runtimeOnly, testImplementation, etc.
         * Maven scopes: compile, provided, runtime, test, system
         */
        fun normalizeScope(scope: String?): String = when (scope?.lowercase()) {
            // Compile-time scopes
            "compile", "implementation", "api" -> SCOPE_COMPILE
            // Runtime-only scopes
            "runtime", "runtimeonly" -> SCOPE_RUNTIME
            // Test scopes
            "test", "testimplementation", "testcompile", "testruntimeonly" -> SCOPE_TEST
            // Provided/compile-only scopes
            "provided", "compileonly", "system" -> SCOPE_PROVIDED
            // Unknown or null defaults to compile
            null, "" -> SCOPE_COMPILE
            // Pass through unknown scopes as-is
            else -> scope.lowercase()
        }
    }
}
