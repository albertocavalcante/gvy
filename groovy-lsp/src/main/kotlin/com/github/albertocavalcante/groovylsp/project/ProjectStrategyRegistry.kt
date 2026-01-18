package com.github.albertocavalcante.groovylsp.project

import com.github.albertocavalcante.groovylsp.config.ServerConfiguration
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path

/**
 * Registry for project strategies.
 *
 * Manages strategy discovery, selection, and lifecycle. Unlike SymbolResolutionStrategy
 * which short-circuits on first success, this registry supports multiple active strategies
 * simultaneously (e.g., Jenkins + Gradle in a mixed workspace).
 *
 * ## Strategy Selection
 * Strategies are sorted by [ProjectStrategy.priority] (descending), then filtered by
 * [ProjectStrategy.canHandle]. All matching strategies become active.
 *
 * ## Capability Lookup
 * Use [findCapability] for type-safe access to strategy capabilities.
 *
 * @param strategies Initial list of strategies to register
 */
class ProjectStrategyRegistry(strategies: List<ProjectStrategy> = emptyList()) {
    private val logger = KotlinLogging.logger {}

    private val strategies: MutableList<ProjectStrategy> =
        strategies.sortedByDescending { it.priority }.toMutableList()

    @PublishedApi
    internal var activeStrategies: List<ProjectStrategy> = emptyList()
        private set

    /**
     * Registers a new strategy.
     * Strategies are kept sorted by priority (descending).
     *
     * @param strategy The strategy to register
     */
    fun register(strategy: ProjectStrategy) {
        strategies.add(strategy)
        strategies.sortByDescending { it.priority }
        logger.debug { "Registered project strategy: ${strategy.id} (priority=${strategy.priority})" }
    }

    /**
     * Selects applicable strategies for the workspace.
     *
     * Unlike resolution pipelines that short-circuit, this method collects **all**
     * strategies that can handle the workspace. This allows composition of features
     * (e.g., Jenkins completion + Gradle dependencies).
     *
     * @param workspaceRoot The root directory of the workspace
     * @param config Server configuration
     * @return List of active strategies for this workspace
     */
    fun selectStrategies(workspaceRoot: Path, config: ServerConfiguration): List<ProjectStrategy> {
        activeStrategies = strategies.filter { strategy ->
            val canHandle = strategy.canHandle(workspaceRoot, config)
            if (canHandle) {
                logger.info {
                    "Project strategy '${strategy.id}' (${strategy.displayName}) active for workspace: $workspaceRoot"
                }
            }
            canHandle
        }

        if (activeStrategies.isEmpty()) {
            logger.warn { "No project strategies matched workspace: $workspaceRoot" }
        } else {
            logger.info {
                "Selected ${activeStrategies.size} project strategies: ${activeStrategies.map { it.id }}"
            }
        }

        return activeStrategies
    }

    /**
     * Finds the first active strategy with the given ID.
     *
     * @param id The strategy ID to find
     * @return The strategy or null if not found/not active
     */
    fun findStrategy(id: String): ProjectStrategy? = activeStrategies.find { it.id == id }

    /**
     * Type-safe capability lookup.
     *
     * Searches active strategies for one implementing the requested capability interface.
     * This is the preferred way to access strategy-specific functionality.
     *
     * Example: `registry.findCapability<JenkinsCapabilities>()?.isJenkinsFile(uri)`
     *
     * @return The first active strategy implementing [T], or null if none found
     */
    inline fun <reified T> findCapability(): T? = activeStrategies.filterIsInstance<T>().firstOrNull()

    /**
     * Shuts down all active strategies.
     * Calls [ProjectStrategy.shutdown] on each active strategy.
     */
    fun shutdown() {
        logger.info { "Shutting down ${activeStrategies.size} project strategies" }
        activeStrategies.forEach { strategy ->
            runCatching { strategy.shutdown() }
                .onFailure { e -> logger.warn { "Error shutting down strategy '${strategy.id}': ${e.message}" } }
        }
        activeStrategies = emptyList()
    }
}
