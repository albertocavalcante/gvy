package com.github.albertocavalcante.groovylsp.compilation

import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe dependency graph tracking file-to-file dependencies.
 *
 * This graph maintains bidirectional dependency relationships:
 * - dependencies: which files a given file depends on (imports/uses)
 * - dependents: which files depend on a given file (reverse lookup)
 *
 * This enables efficient incremental compilation by determining which files
 * need to be recompiled when a file changes.
 *
 * Thread-safety is provided by ConcurrentHashMap for concurrent LSP operations.
 */
class DependencyGraph {
    private val logger = KotlinLogging.logger {}

    /**
     * Maps a file URI to the set of file URIs it depends on (direct dependencies only).
     * Example: if FileA imports FileB, then dependencies[FileA] contains FileB.
     */
    private val dependencies: MutableMap<URI, MutableSet<URI>> = ConcurrentHashMap()

    /**
     * Maps a file URI to the set of file URIs that depend on it (reverse index).
     * Example: if FileA imports FileB, then dependents[FileB] contains FileA.
     */
    private val dependents: MutableMap<URI, MutableSet<URI>> = ConcurrentHashMap()

    /**
     * Adds a dependency relationship: fromUri depends on toUri.
     *
     * This updates both the forward dependency map and the reverse dependent map.
     *
     * @param fromUri The file that has the dependency
     * @param toUri The file being depended upon
     */
    fun addDependency(fromUri: URI, toUri: URI) {
        // Add to forward dependency map
        dependencies.computeIfAbsent(fromUri) { ConcurrentHashMap.newKeySet() }.add(toUri)

        // Add to reverse dependent map
        dependents.computeIfAbsent(toUri) { ConcurrentHashMap.newKeySet() }.add(fromUri)

        logger.debug { "Added dependency: $fromUri -> $toUri" }
    }

    /**
     * Removes a file from the dependency graph, clearing all its dependencies and dependents.
     *
     * This is called when a file is deleted from the workspace.
     *
     * @param uri The file to remove
     */
    fun removeFile(uri: URI) {
        // Remove all dependencies this file has
        dependencies.remove(uri)?.forEach { dependencyUri ->
            // Remove this file from the dependent's list
            dependents[dependencyUri]?.remove(uri)
            if (dependents[dependencyUri]?.isEmpty() == true) {
                dependents.remove(dependencyUri)
            }
        }

        // Remove all dependents of this file
        dependents.remove(uri)?.forEach { dependentUri ->
            // Remove this file from the dependent's dependency list
            dependencies[dependentUri]?.remove(uri)
            if (dependencies[dependentUri]?.isEmpty() == true) {
                dependencies.remove(dependentUri)
            }
        }

        logger.debug { "Removed file from dependency graph: $uri" }
    }

    /**
     * Gets all files that depend on the given file (transitive closure).
     *
     * This performs a breadth-first search to find all files that directly or
     * indirectly depend on the given file. Handles circular dependencies gracefully
     * using a visited set.
     *
     * @param uri The file to find dependents for
     * @return Set of all files that depend on the given file (transitively)
     */
    fun getDependents(uri: URI): Set<URI> {
        val result = mutableSetOf<URI>()
        val visited = mutableSetOf<URI>()
        val queue = ArrayDeque<URI>()

        // Start with direct dependents
        dependents[uri]?.forEach { queue.add(it) }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()

            // Skip if already visited (handles cycles)
            if (!visited.add(current)) {
                continue
            }

            result.add(current)

            // Add this file's dependents to the queue
            dependents[current]?.forEach { dependent ->
                if (dependent !in visited) {
                    queue.add(dependent)
                }
            }
        }

        return result
    }

    /**
     * Gets all files that the given file depends on (direct dependencies only).
     *
     * @param uri The file to find dependencies for
     * @return Set of all files that the given file directly depends on
     */
    fun getDependencies(uri: URI): Set<URI> = dependencies[uri]?.toSet() ?: emptySet()

    /**
     * Gets bounded compilation sources for a file - its direct dependencies and dependents.
     *
     * This provides a bounded set of workspace sources for compilation, avoiding
     * the O(n²) behavior of including ALL workspace sources.
     *
     * Issue #743: Normalize parse modes and cache authority for cross-file resolution
     *
     * @param uri The file being compiled
     * @return Set of URIs that should be included in compilation (direct deps + dependents)
     */
    fun getCompilationSources(uri: URI): Set<URI> {
        val directDependencies = getDependencies(uri)
        val directDependents = dependents[uri]?.toSet() ?: emptySet()

        val result = directDependencies + directDependents

        logger.debug {
            "Bounded compilation sources for $uri: ${directDependencies.size} dependencies + ${directDependents.size} dependents = ${result.size} total"
        }

        return result
    }

    /**
     * Checks if the dependency graph has any information about a file.
     * Used to determine if bounded compilation is possible.
     */
    fun hasInfo(uri: URI): Boolean = dependencies.containsKey(uri) || dependents.containsKey(uri)

    /**
     * Gets all files affected by changes to the given files.
     *
     * This includes:
     * 1. The changed files themselves
     * 2. All files that transitively depend on any of the changed files
     *
     * This is the primary method used for incremental compilation to determine
     * which files need to be recompiled.
     *
     * @param changedUris Set of URIs that have changed
     * @return Set of all files that need to be recompiled (including the changed files)
     */
    fun getAffectedFiles(changedUris: Set<URI>): Set<URI> {
        val affected = mutableSetOf<URI>()

        // Add all changed files
        affected.addAll(changedUris)

        // Add all transitive dependents of changed files
        changedUris.forEach { changedUri ->
            affected.addAll(getDependents(changedUri))
        }

        logger.debug { "Affected files for changes to ${changedUris.size}: ${affected.size} total affected" }

        return affected
    }

    /**
     * Updates dependencies for a file by extracting them from its compiled AST.
     *
     * This replaces all existing dependencies for the file with the new set
     * extracted from the module.
     *
     * Dependencies are extracted from:
     * - Import statements
     * - Superclass references
     * - Interface implementations
     *
     * @param uri The file URI being updated
     * @param moduleNode The compiled AST module
     * @param workspaceIndex Map from class names to their file URIs (for resolving references)
     */
    fun updateFromModule(uri: URI, moduleNode: ModuleNode, workspaceIndex: Map<String, URI>) {
        val newDependencies = mutableSetOf<URI>()

        // Extract dependencies from imports
        moduleNode.imports.forEach { importNode ->
            processImportNode(importNode.className ?: importNode.type?.name, workspaceIndex, newDependencies)
        }

        // Extract dependencies from star imports
        moduleNode.starImports.forEach { importNode ->
            processStarImport(importNode.type?.name ?: importNode.className, workspaceIndex, newDependencies)
        }

        // Extract dependencies from static imports
        moduleNode.staticImports.values.sortedBy { it.className ?: it.type?.name ?: "" }.forEach { importNode ->
            processSimpleImport(importNode.type?.name ?: importNode.className, workspaceIndex, newDependencies)
        }

        moduleNode.staticStarImports.values.sortedBy { it.className ?: it.type?.name ?: "" }.forEach { importNode ->
            processSimpleImport(importNode.type?.name ?: importNode.className, workspaceIndex, newDependencies)
        }

        // Extract dependencies from classes in the module
        moduleNode.classes.forEach { classNode ->
            extractDependenciesFromClass(classNode, workspaceIndex, newDependencies)
        }

        // Update the graph with new dependencies
        setDependencies(uri, newDependencies)

        logger.debug { "Updated dependencies for $uri: ${newDependencies.size} dependencies" }
    }

    /**
     * Process an import node and extract dependencies.
     *
     * Tries both fully qualified name and simple name to find dependencies.
     */
    private fun processImportNode(
        importedClassName: String?,
        workspaceIndex: Map<String, URI>,
        dependencies: MutableSet<URI>,
    ) {
        if (importedClassName == null) return

        logger.trace {
            "Processing import: className=$importedClassName, available keys=${workspaceIndex.keys}"
        }

        // Try both fully qualified name and simple name
        workspaceIndex[importedClassName]?.let { dependencyUri ->
            dependencies.add(dependencyUri)
            logger.trace { "Found dependency for $importedClassName: $dependencyUri" }
        }

        // Also try extracting simple name from FQN
        val simpleName = importedClassName.substringAfterLast('.')
        if (simpleName != importedClassName) {
            workspaceIndex[simpleName]?.let { dependencyUri ->
                dependencies.add(dependencyUri)
                logger.trace { "Found dependency for simple name $simpleName: $dependencyUri" }
            }
        }
    }

    /**
     * Process a star import and extract dependencies on all classes in the package.
     */
    private fun processStarImport(
        packageName: String?,
        workspaceIndex: Map<String, URI>,
        dependencies: MutableSet<URI>,
    ) {
        if (packageName == null) return

        workspaceIndex.entries.sortedBy { it.key }.forEach { (className, uri) ->
            // Check if the class is directly in the package (not a subpackage)
            if (className.startsWith("$packageName.") && !className.substringAfter("$packageName.").contains('.')) {
                dependencies.add(uri)
            }
        }
    }

    /**
     * Process a static or static-star import and extract dependencies.
     *
     * Only tries the exact class name without FQN resolution.
     */
    private fun processSimpleImport(
        importedClassName: String?,
        workspaceIndex: Map<String, URI>,
        dependencies: MutableSet<URI>,
    ) {
        if (importedClassName == null) return

        workspaceIndex[importedClassName]?.let { dependencyUri ->
            dependencies.add(dependencyUri)
        }
    }

    /**
     * Extracts dependencies from a ClassNode.
     */
    private fun extractDependenciesFromClass(
        classNode: ClassNode,
        workspaceIndex: Map<String, URI>,
        dependencies: MutableSet<URI>,
    ) {
        // Extract from superclass
        classNode.superClass?.let { superClass ->
            extractDependency(superClass, workspaceIndex, dependencies)
        }

        // Extract from interfaces
        classNode.interfaces?.forEach { interfaceNode ->
            extractDependency(interfaceNode, workspaceIndex, dependencies)
        }

        // Note: We could also extract from field types, method parameter types, etc.
        // For now, focusing on import-level and inheritance-level dependencies for performance.
    }

    /**
     * Extracts a dependency from a ClassNode reference.
     */
    private fun extractDependency(
        classNode: ClassNode,
        workspaceIndex: Map<String, URI>,
        dependencies: MutableSet<URI>,
    ) {
        val className = classNode.name

        // Skip primitive types and JDK types
        if (className.startsWith("java.") || className.startsWith("groovy.")) {
            return
        }

        // Look up the class in the workspace index
        workspaceIndex[className]?.let { dependencyUri ->
            dependencies.add(dependencyUri)
        }
    }

    /**
     * Replaces all dependencies for a file with a new set.
     *
     * This removes old dependency relationships and establishes new ones.
     */
    private fun setDependencies(uri: URI, newDependencies: Set<URI>) {
        // Remove old dependencies
        val oldDependencies = dependencies.remove(uri) ?: emptySet()

        // Clear this file from old dependencies' dependent lists
        oldDependencies.forEach { oldDependency ->
            dependents[oldDependency]?.remove(uri)
            if (dependents[oldDependency]?.isEmpty() == true) {
                dependents.remove(oldDependency)
            }
        }

        // Add new dependencies
        if (newDependencies.isNotEmpty()) {
            dependencies[uri] = ConcurrentHashMap.newKeySet<URI>().apply {
                addAll(newDependencies)
            }

            // Update dependent lists
            newDependencies.forEach { dependency ->
                dependents.computeIfAbsent(dependency) { ConcurrentHashMap.newKeySet() }.add(uri)
            }
        }
    }

    /**
     * Gets statistics about the dependency graph.
     */
    fun getStatistics(): DependencyGraphStatistics {
        val totalDependencies = dependencies.values.sumOf { it.size }
        val filesWithDependencies = dependencies.size
        val filesWithDependents = dependents.size

        return DependencyGraphStatistics(
            totalFiles = (dependencies.keys + dependents.keys).size,
            filesWithDependencies = filesWithDependencies,
            filesWithDependents = filesWithDependents,
            totalDependencyEdges = totalDependencies,
        )
    }
}

/**
 * Statistics about the dependency graph.
 */
data class DependencyGraphStatistics(
    val totalFiles: Int,
    val filesWithDependencies: Int,
    val filesWithDependents: Int,
    val totalDependencyEdges: Int,
)
