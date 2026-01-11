package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI

/**
 * Result of incremental compilation.
 *
 * @property modules Map of all modules in the workspace (after recompilation)
 * @property errors List of compilation errors
 * @property success Whether compilation succeeded
 * @property recompiledFiles Set of file URIs that were recompiled
 */
data class IncrementalCompilationResult(
    val modules: Map<URI, org.codehaus.groovy.ast.ModuleNode>,
    val errors: List<CompilationError>,
    val success: Boolean,
    val recompiledFiles: Set<URI>,
)

/**
 * Orchestrates incremental compilation with dependency tracking.
 *
 * This compiler uses a dependency graph to determine which files need to be
 * recompiled when a subset of files change. This avoids full workspace
 * recompilation on every file change.
 *
 * The compilation process:
 * 1. Determine affected files (changed files + their transitive dependents)
 * 2. Recompile only the affected files
 * 3. Update the dependency graph from the new ASTs
 * 4. Update the semantic database for all recompiled files
 *
 * @param workspaceCompiler The workspace compiler for compiling files
 * @param dependencyGraph The dependency graph tracking file dependencies
 * @param semanticDb The semantic database for storing symbol information
 */
class IncrementalCompiler(
    private val workspaceCompiler: WorkspaceCompiler,
    private val dependencyGraph: DependencyGraph,
    private val semanticDb: GroovySemanticDB,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Performs initial workspace compilation and populates the dependency graph.
     *
     * This should be called once at startup to establish the initial state.
     * After this, use [compile] for incremental updates.
     *
     * @return WorkspaceCompilationResult with all compiled modules
     */
    suspend fun initialCompile(): WorkspaceCompilationResult {
        logger.info { "Performing initial workspace compilation" }
        val startTime = System.currentTimeMillis()

        // Compile all workspace files
        val result = workspaceCompiler.compileWorkspace()

        // Build dependency graph from compiled modules
        buildDependencyGraph(result.modules)

        // Build semantic documents for cross-file symbol resolution
        buildSemanticDocuments(result.modules)

        val elapsed = System.currentTimeMillis() - startTime
        logger.info {
            "Initial compilation completed in ${elapsed}ms: " +
                "${result.modules.size} modules, ${result.errors.size} errors"
        }

        return result
    }

    /**
     * Incrementally compiles only changed files and their dependents.
     *
     * This method:
     * 1. Uses the dependency graph to find all files affected by the changes
     * 2. Recompiles only those files
     * 3. Updates the dependency graph with new dependencies
     * 4. Updates the semantic database
     *
     * @param changedUris Set of URIs that have changed
     * @return IncrementalCompilationResult with recompilation details
     */
    suspend fun compile(changedUris: Set<URI>): IncrementalCompilationResult {
        if (changedUris.isEmpty()) {
            logger.debug { "No changed files, skipping compilation" }
            return IncrementalCompilationResult(
                modules = emptyMap(),
                errors = emptyList(),
                success = true,
                recompiledFiles = emptySet(),
            )
        }

        logger.info { "Incremental compile for ${changedUris.size} changed files" }
        val startTime = System.currentTimeMillis()

        // Determine all files that need recompilation
        val affectedFiles = dependencyGraph.getAffectedFiles(changedUris)
        logger.info { "Found ${affectedFiles.size} affected files (including transitive dependents)" }

        // For now, do a full workspace recompilation to ensure correctness
        // TODO: Implement selective recompilation of only affected files
        val result = workspaceCompiler.compileWorkspace()

        // Rebuild dependency graph from all modules
        buildDependencyGraph(result.modules)

        // Rebuild semantic documents for cross-file symbol resolution
        buildSemanticDocuments(result.modules)

        val elapsed = System.currentTimeMillis() - startTime
        logger.info {
            "Incremental compilation completed in ${elapsed}ms: " +
                "${affectedFiles.size} files recompiled, ${result.errors.size} errors"
        }

        return IncrementalCompilationResult(
            modules = result.modules,
            errors = result.errors,
            success = result.success,
            recompiledFiles = affectedFiles,
        )
    }

    /**
     * Builds the dependency graph from compiled modules.
     *
     * Extracts dependencies from each module's imports, superclasses, and interfaces,
     * then updates the graph.
     */
    private fun buildDependencyGraph(modules: Map<URI, org.codehaus.groovy.ast.ModuleNode>) {
        logger.debug { "Building dependency graph from ${modules.size} modules" }

        // Build workspace index: class name -> file URI
        val workspaceIndex = buildWorkspaceIndex(modules)

        // Update dependency graph for each module
        modules.forEach { (uri, moduleNode) ->
            dependencyGraph.updateFromModule(uri, moduleNode, workspaceIndex)
        }

        val stats = dependencyGraph.getStatistics()
        logger.debug {
            "Dependency graph built: ${stats.totalFiles} files, ${stats.totalDependencyEdges} dependency edges"
        }
    }

    /**
     * Builds a workspace index mapping class names to their file URIs.
     *
     * This is used to resolve class references in imports and type references
     * to actual workspace files.
     */
    private fun buildWorkspaceIndex(modules: Map<URI, org.codehaus.groovy.ast.ModuleNode>): Map<String, URI> {
        val index = mutableMapOf<String, URI>()

        modules.forEach { (uri, moduleNode) ->
            moduleNode.classes.forEach { classNode ->
                val className = classNode.name
                // Store both simple name and fully qualified name
                index[className] = uri

                // Also store simple name if it's different
                val simpleName = className.substringAfterLast('.')
                if (simpleName != className) {
                    // Only overwrite if not already present (prefer first occurrence)
                    index.putIfAbsent(simpleName, uri)
                }
            }
        }

        logger.trace { "Built workspace index with ${index.size} class entries" }
        return index
    }

    /**
     * Builds semantic documents from compiled modules and populates SemanticDB.
     * This enables cross-file symbol resolution via SemanticDBResolutionStrategy.
     */
    private fun buildSemanticDocuments(modules: Map<URI, org.codehaus.groovy.ast.ModuleNode>) {
        logger.debug { "Building semantic documents from ${modules.size} modules" }

        modules.forEach { (uri, moduleNode) ->
            try {
                val builder = SemanticDocumentBuilder(moduleNode, uri)
                val semanticDoc = builder.build()
                semanticDb.updateDocument(uri, semanticDoc)
                logger.trace { "Built semantic document for $uri with ${semanticDoc.symbols.size} symbols" }
            } catch (e: Exception) {
                logger.warn { "Failed to build semantic document for $uri: ${e.message}" }
            }
        }

        logger.info { "Semantic documents built: ${modules.size} files indexed" }
    }
}
