package com.github.albertocavalcante.groovylsp.providers.dependencies

/**
 * Information about a single dependency.
 */
data class DependencyInfo(
    // Maven coordinates (e.g., "org.apache.commons:commons-lang3") when available,
    // otherwise artifact name from JAR
    val name: String,
    val version: String, // e.g., "3.12.0"
    val scope: String, // "compile", "runtime", "test", "provided"
    val path: String, // file:// URI to JAR
    val isTransitive: Boolean, // false = direct dependency
)

/**
 * Parameters for the groovy/workspace/dependencies request.
 */
data class GetDependenciesParams(val workspaceUri: String)

/**
 * Result of dependency resolution.
 */
data class DependenciesResult(
    val dependencies: List<DependencyInfo>,
    val buildTool: String, // "gradle" | "maven" | "unknown"
)
