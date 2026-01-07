package com.github.groovylsp.bsp.client

import ch.epfl.scala.bsp4j.BuildServerCapabilities

/**
 * Type-safe wrapper for BSP server capabilities.
 *
 * Provides convenient methods to check which optional BSP features the server supports.
 * Based on the [BuildServerCapabilities] returned during initialization.
 *
 * This follows the pattern used by Metals for graceful degradation when servers
 * don't support certain features.
 *
 * @param capabilities The capabilities returned by the server during initialization
 */
class BspCapabilities(private val capabilities: BuildServerCapabilities) {

    /**
     * Check if the server supports compile requests.
     * When false, attempting to compile will fail.
     */
    fun supportsCompile(): Boolean = capabilities.compileProvider?.languageIds?.isNotEmpty() == true

    /**
     * Check if the server supports test execution.
     * When false, test requests should be skipped.
     */
    fun supportsTest(): Boolean = capabilities.testProvider?.languageIds?.isNotEmpty() == true

    /**
     * Check if the server supports run requests.
     * When false, run requests will not work.
     */
    fun supportsRun(): Boolean = capabilities.runProvider?.languageIds?.isNotEmpty() == true

    /**
     * Check if the server can provide dependency sources (e.g., downloading Maven artifacts).
     * When false, IDE won't be able to navigate to library sources.
     */
    fun supportsDependencySources(): Boolean = capabilities.dependencySourcesProvider == true

    /**
     * Check if the server can provide dependency modules (transitive dependencies).
     * When false, full dependency tree won't be available.
     */
    fun supportsDependencyModules(): Boolean = capabilities.dependencyModulesProvider == true

    /**
     * Check if the server supports build target resources.
     * When false, resource files may not be properly indexed.
     */
    fun supportsResources(): Boolean = capabilities.resourcesProvider == true

    /**
     * Check if the server supports output paths.
     * When false, output directory information won't be available.
     */
    fun supportsOutputPaths(): Boolean = capabilities.outputPathsProvider == true

    /**
     * Check if the server supports inverse sources (find build targets by source file).
     * When false, workspace symbol search may be limited.
     */
    fun supportsInverseSources(): Boolean = capabilities.inverseSourcesProvider == true

    /**
     * Check if the server can be reloaded after build file changes.
     * When false, full reconnection is needed after build changes.
     */
    fun canReload(): Boolean = capabilities.canReload == true

    /**
     * Get the list of languages supported for compilation.
     * Returns empty list if compile is not supported.
     */
    fun supportedCompileLanguages(): List<String> = capabilities.compileProvider?.languageIds ?: emptyList()

    /**
     * Get the list of languages supported for testing.
     * Returns empty list if test is not supported.
     */
    fun supportedTestLanguages(): List<String> = capabilities.testProvider?.languageIds ?: emptyList()

    /**
     * Get the list of languages supported for running.
     * Returns empty list if run is not supported.
     */
    fun supportedRunLanguages(): List<String> = capabilities.runProvider?.languageIds ?: emptyList()

    /**
     * Check if a specific language is supported for compilation.
     */
    fun supportsCompileLanguage(languageId: String): Boolean = languageId in supportedCompileLanguages()

    /**
     * Check if a specific language is supported for testing.
     */
    fun supportsTestLanguage(languageId: String): Boolean = languageId in supportedTestLanguages()

    /**
     * Check if a specific language is supported for running.
     */
    fun supportsRunLanguage(languageId: String): Boolean = languageId in supportedRunLanguages()

    /**
     * Get the raw capabilities object for advanced use cases.
     */
    fun raw(): BuildServerCapabilities = capabilities
}
