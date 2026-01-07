package com.github.groovylsp.bsp.model

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.slf4j.LoggerFactory

/**
 * Directed Acyclic Graph (DAG) for build target dependencies.
 *
 * Provides graph operations for dependency analysis:
 * - Topological sorting for build order
 * - Transitive dependency computation
 * - Reverse dependencies (dependents)
 * - Cycle detection
 *
 * Based on Bloop's dependency graph pattern for build orchestration.
 *
 * ## Usage
 * ```kotlin
 * val graph = DependencyGraph(cache)
 * val buildOrder = graph.topologicalSort()
 * val allDeps = graph.getTransitiveDependencies(targetId)
 * val cycles = graph.findCycles()
 * ```
 *
 * @property cache The build target cache containing dependency information
 */
class DependencyGraph(private val cache: BuildTargetCache) {
    private val logger = LoggerFactory.getLogger(DependencyGraph::class.java)

    /**
     * Computes a topological sort of all build targets.
     *
     * Returns targets in dependency order: dependencies appear before dependents.
     * This is the order in which targets should be built.
     *
     * If cycles are detected, they are logged as warnings and broken arbitrarily.
     *
     * @return List of target IDs in build order (dependencies first)
     */
    fun topologicalSort(): List<BuildTargetIdentifier> {
        val targets = cache.all()
        if (targets.isEmpty()) return emptyList()

        val sorted = mutableListOf<BuildTargetIdentifier>()
        val visited = mutableSetOf<BuildTargetIdentifier>()
        val visiting = mutableSetOf<BuildTargetIdentifier>()

        fun visit(targetId: BuildTargetIdentifier): Boolean {
            if (targetId in visited) return true
            if (targetId in visiting) {
                // Cycle detected
                logger.warn("Cycle detected involving target: {}", targetId.uri)
                return false
            }

            visiting.add(targetId)

            // Visit dependencies first
            val dependencies = cache.getTargetDependencies(targetId)
            for (dep in dependencies) {
                if (!visit(dep)) {
                    // Cycle in dependency, skip it
                    logger.debug("Skipping cyclic dependency: {} -> {}", targetId.uri, dep.uri)
                }
            }

            visiting.remove(targetId)
            visited.add(targetId)
            sorted.add(targetId)

            return true
        }

        // Visit all targets
        for (target in targets) {
            visit(target.id)
        }

        logger.debug("Topological sort produced {} targets", sorted.size)
        return sorted
    }

    /**
     * Computes all transitive dependencies of a target.
     *
     * Returns the complete set of targets that must be built before this target,
     * including indirect dependencies.
     *
     * @param targetId The target identifier
     * @return Set of all transitive dependency IDs (excluding the target itself)
     */
    fun getTransitiveDependencies(targetId: BuildTargetIdentifier): Set<BuildTargetIdentifier> {
        val result = mutableSetOf<BuildTargetIdentifier>()
        val visited = mutableSetOf<BuildTargetIdentifier>()

        fun visit(id: BuildTargetIdentifier) {
            if (id in visited) return
            visited.add(id)

            val deps = cache.getTargetDependencies(id)
            for (dep in deps) {
                result.add(dep)
                visit(dep)
            }
        }

        visit(targetId)
        return result
    }

    /**
     * Finds all targets that depend on the given target (reverse dependencies).
     *
     * Returns the set of targets that must be rebuilt if this target changes.
     *
     * @param targetId The target identifier
     * @return Set of dependent target IDs (targets that depend on this one)
     */
    fun getDependents(targetId: BuildTargetIdentifier): Set<BuildTargetIdentifier> {
        val dependents = mutableSetOf<BuildTargetIdentifier>()

        for (target in cache.all()) {
            val deps = cache.getTargetDependencies(target.id)
            if (targetId in deps) {
                dependents.add(target.id)
            }
        }

        logger.trace("Found {} dependents of {}", dependents.size, targetId.uri)
        return dependents
    }

    /**
     * Finds all transitive dependents of a target.
     *
     * Returns all targets that transitively depend on the given target.
     * Useful for determining the full rebuild scope when a target changes.
     *
     * @param targetId The target identifier
     * @return Set of all transitive dependent IDs
     */
    fun getTransitiveDependents(targetId: BuildTargetIdentifier): Set<BuildTargetIdentifier> {
        val result = mutableSetOf<BuildTargetIdentifier>()
        val visited = mutableSetOf<BuildTargetIdentifier>()

        fun visit(id: BuildTargetIdentifier) {
            if (id in visited) return
            visited.add(id)

            val deps = getDependents(id)
            for (dep in deps) {
                result.add(dep)
                visit(dep)
            }
        }

        visit(targetId)
        return result
    }

    /**
     * Detects cycles in the dependency graph.
     *
     * Returns a list of cycles, where each cycle is represented as a list of
     * target IDs forming a circular dependency.
     *
     * An empty list indicates no cycles (valid DAG).
     *
     * @return List of cycles, each cycle is a list of target IDs
     */
    fun findCycles(): List<List<BuildTargetIdentifier>> {
        val cycles = mutableListOf<List<BuildTargetIdentifier>>()
        val visited = mutableSetOf<BuildTargetIdentifier>()
        val recStack = mutableListOf<BuildTargetIdentifier>()

        fun detectCycle(targetId: BuildTargetIdentifier): Boolean {
            if (targetId in recStack) {
                // Found a cycle
                val cycleStart = recStack.indexOf(targetId)
                val cycle = recStack.subList(cycleStart, recStack.size) + targetId
                cycles.add(cycle)
                logger.warn("Cycle detected: {}", cycle.joinToString(" -> ") { it.uri })
                return true
            }

            if (targetId in visited) return false

            visited.add(targetId)
            recStack.add(targetId)

            val dependencies = cache.getTargetDependencies(targetId)
            for (dep in dependencies) {
                detectCycle(dep)
            }

            recStack.remove(targetId)
            return false
        }

        // Check all targets for cycles
        for (target in cache.all()) {
            if (target.id !in visited) {
                detectCycle(target.id)
            }
        }

        if (cycles.isNotEmpty()) {
            logger.error("Found {} cycles in dependency graph", cycles.size)
        } else {
            logger.debug("No cycles detected in dependency graph")
        }

        return cycles
    }

    /**
     * Checks if the graph is acyclic (valid DAG).
     *
     * @return true if the graph has no cycles
     */
    fun isAcyclic(): Boolean = findCycles().isEmpty()

    /**
     * Computes the build order for a specific set of targets.
     *
     * Returns a topologically sorted list containing only the specified targets
     * and their dependencies.
     *
     * @param targetIds Set of target IDs to build
     * @return List of target IDs in build order (dependencies first)
     */
    fun buildOrder(targetIds: Set<BuildTargetIdentifier>): List<BuildTargetIdentifier> {
        // Compute transitive closure of all targets to build
        val allTargets = mutableSetOf<BuildTargetIdentifier>()
        for (targetId in targetIds) {
            allTargets.add(targetId)
            allTargets.addAll(getTransitiveDependencies(targetId))
        }

        // Filter topological sort to only include relevant targets
        val fullOrder = topologicalSort()
        return fullOrder.filter { it in allTargets }
    }

    override fun toString(): String {
        val targets = cache.all()
        val edges = targets.sumOf { cache.getTargetDependencies(it.id).size }
        return "DependencyGraph(targets=${targets.size}, edges=$edges)"
    }
}
