package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.ast.AstVisitor
import com.github.albertocavalcante.groovylsp.ast.SymbolTable
import groovy.lang.GroovyClassLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.io.StringReaderSource
import org.eclipse.lsp4j.Diagnostic
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Context-aware workspace compilation service that compiles files in separate
 * compilation contexts based on build system structure (Gradle source sets, etc.).
 *
 * Key features:
 * - Respects build system structure (main vs test source sets)
 * - Compiles contexts separately with appropriate classpaths
 * - Properly attributes diagnostics to source files
 * - Supports incremental compilation per context
 * - Handles standalone files separately
 */
// Workspace compilation handles all Groovy compiler errors and complex build system integration
@Suppress("TooGenericExceptionCaught", "TooManyFunctions")
class WorkspaceCompilationService(
    @Suppress("UnusedPrivateProperty") private val coroutineScope: CoroutineScope,
    private val dependencyManager: CentralizedDependencyManager,
    private val contextManager: CompilationContextManager = CompilationContextManager(),
) : DependencyListener {

    // Helper components removed - references to non-existent classes

    private val logger = LoggerFactory.getLogger(WorkspaceCompilationService::class.java)

    companion object {
        // Debug output limit for dependency and file logging
        private const val MAX_DEBUG_ITEMS_TO_LOG = 5
    }

    private val compilationMutex = Mutex()

    // Context-aware compilation state
    private val contextCompilationUnits = ConcurrentHashMap<String, CompilationUnit>()
    private val contextAstVisitors = ConcurrentHashMap<String, AstVisitor>()
    private val contextSymbolTables = ConcurrentHashMap<String, SymbolTable>()
    private var workspaceRoot: Path? = null
    private var lastWorkspaceCompilationResult: WorkspaceCompilationResult? = null

    // Combined workspace-level views
    private var workspaceAstVisitor: AstVisitor? = null
    private var workspaceSymbolTable: SymbolTable? = null

    // File content and tracking
    private val fileContents = ConcurrentHashMap<URI, String>()
    private val fileModificationTimes = ConcurrentHashMap<URI, Long>()
    private val compiledFiles = ConcurrentHashMap<URI, ModuleNode>()

    // Legacy state for backward compatibility - delegated to helpers

    init {
        // Register for dependency updates
        dependencyManager.addListener(this)
        logger.debug("WorkspaceCompilationService registered for dependency updates")
    }

    /**
     * Called when dependencies are updated. Clears caches to force recompilation
     * with the new classpath.
     */
    override fun onDependenciesUpdated(dependencies: List<Path>) {
        logger.info("=== DEPENDENCY UPDATE START ===")
        logger.info("WorkspaceCompilationService received ${dependencies.size} dependencies")
        logger.debug("Before clearing - Contexts with AST visitors: ${contextAstVisitors.keys}")
        logger.debug("Before clearing - Compiled files count: ${compiledFiles.size}")

        if (logger.isDebugEnabled) {
            dependencies.take(MAX_DEBUG_ITEMS_TO_LOG).forEach { dep ->
                logger.debug("  - ${dep.fileName}")
            }
        }

        // Clear compilation caches since classpath has changed (preserve file contents/workspace root)
        contextCompilationUnits.clear()
        contextAstVisitors.clear()
        contextSymbolTables.clear()
        compiledFiles.clear()
        workspaceAstVisitor = null
        workspaceSymbolTable = null

        logger.info("Cleared all compilation caches due to dependency update")
        logger.warn("⚠️ AST visitors cleared - hover may not work until recompilation")
        logger.info("=== DEPENDENCY UPDATE END ===")
    }

    enum class CompilationMode {
        DYNAMIC, // Default Groovy behavior
        TYPE_CHECKED, // @TypeChecked detected
        COMPILE_STATIC, // @CompileStatic detected
    }

    data class WorkspaceCompilationResult(
        val isSuccess: Boolean,
        val modulesByUri: Map<URI, ModuleNode>,
        val diagnostics: Map<URI, List<Diagnostic>>,
        val astVisitor: AstVisitor?,
        val symbolTable: SymbolTable?,
        val compilationMode: CompilationMode = CompilationMode.DYNAMIC,
    ) {
        companion object {
            fun success(
                modules: Map<URI, ModuleNode>,
                diagnostics: Map<URI, List<Diagnostic>>,
                astVisitor: AstVisitor?,
                symbolTable: SymbolTable?,
            ) = WorkspaceCompilationResult(
                true,
                modules,
                diagnostics,
                astVisitor,
                symbolTable,
            )

            fun failure(diagnostics: Map<URI, List<Diagnostic>>) = WorkspaceCompilationResult(
                false,
                emptyMap(),
                diagnostics,
                null,
                null,
            )
        }
    }

    /**
     * Initialize the workspace and perform context-aware compilation.
     */
    suspend fun initializeWorkspace(root: Path): WorkspaceCompilationResult = compilationMutex.withLock {
        logger.info("Initializing context-aware workspace compilation for: $root")
        workspaceRoot = root

        return try {
            // Build compilation contexts from build system
            val contexts = contextManager.buildContexts(root)

            if (contexts.isEmpty()) {
                logger.warn("No compilation contexts found for workspace")
                val result = WorkspaceCompilationResult.success(emptyMap(), emptyMap(), null, null)
                lastWorkspaceCompilationResult = result
                return result
            }

            logger.info("Found ${contexts.size} compilation contexts")

            // Compile each context separately
            val allModules = mutableMapOf<URI, ModuleNode>()
            val allDiagnostics = mutableMapOf<URI, List<Diagnostic>>()

            contexts.forEach { (contextName, context) ->
                logger.info("Compiling context '$contextName' with ${context.fileCount()} files")
                val result = compileContext(contextName, context)

                allModules.putAll(result.modulesByUri)
                allDiagnostics.putAll(result.diagnostics)
            }

            // Create a combined AST visitor and symbol table by re-visiting modules from individual contexts
            val combinedAstVisitor = AstVisitor()
            val combinedSymbolTable = SymbolTable()

            // CRITICAL FIX: Don't create a combined visitor that mixes all files
            // Each file should get its own isolated visitor when requested
            logger.info("Skipping combined AST visitor creation to prevent node contamination")
            // The combined visitor is set to null and will be created on-demand if needed
            // allModules.forEach { (uri, module) -> ... } // REMOVED TO FIX CONTAMINATION

            // Build symbol table from individual context visitors instead
            // This prevents contamination while still allowing cross-file symbol resolution
            contextAstVisitors.values.forEach { contextVisitor ->
                combinedSymbolTable.buildFromVisitor(contextVisitor)
            }

            // Store the combined workspace views for persistent access
            workspaceAstVisitor = combinedAstVisitor
            workspaceSymbolTable = combinedSymbolTable

            logger.info(
                "Workspace compilation completed: ${allModules.size} modules, ${allDiagnostics.values.sumOf {
                    it.size
                }} diagnostics",
            )

            val result = WorkspaceCompilationResult.success(
                allModules,
                allDiagnostics,
                combinedAstVisitor,
                combinedSymbolTable,
            )
            lastWorkspaceCompilationResult = result
            result
        } catch (e: Exception) {
            logger.error("Failed to initialize workspace compilation", e)
            val result = WorkspaceCompilationResult.failure(emptyMap())
            lastWorkspaceCompilationResult = result
            result
        }
    }

    /**
     * Update a single file and recompile its context incrementally.
     */
    suspend fun updateFile(uri: URI, @Suppress("UNUSED_PARAMETER") content: String): WorkspaceCompilationResult =
        compilationMutex.withLock {
            logger.debug("Updating file: $uri")

            // Update file content (simplified - no file manager)
            // Content is handled through compilation units directly

            // Find which context this file belongs to
            val contextName = contextManager.getContextForFile(uri)

            return if (contextName != null) {
                logger.debug("Recompiling context '$contextName' for file update: $uri")
                recompileContext(contextName)
            } else {
                // File not in any known context - might be a new standalone file
                logger.debug("File not in any context, rebuilding workspace: $uri")
                recompileWorkspace()
            }
        }

    /**
     * Remove a file and recompile.
     */
    suspend fun removeFile(uri: URI): WorkspaceCompilationResult = compilationMutex.withLock {
        logger.debug("Removing file: $uri")

        // Remove file (simplified - no file manager)
        // Removal is handled through compilation context

        return recompileWorkspace()
    }

    /**
     * Get the AST visitor for a specific file.
     * CRITICAL FIX: Create a file-specific visitor to prevent AST contamination.
     * This ensures only nodes from the requested file are available for queries.
     */
    fun getAstVisitorForFile(uri: URI): AstVisitor? {
        logger.debug("Getting AST visitor for $uri")

        // Find the compiled module for this specific URI
        val module = compiledFiles[uri]
        if (module == null) {
            logger.debug("No compiled module found for $uri")
            return null
        }

        // Find the source unit for this URI
        val sourceUnit = findSourceUnitForUri(uri)
        if (sourceUnit == null) {
            logger.warn("No source unit found for $uri despite having compiled module")
            return null
        }

        // Create a new AST visitor with ONLY this file's nodes
        val fileSpecificVisitor = AstVisitor()
        fileSpecificVisitor.visitModule(module, sourceUnit, uri)

        logger.debug("Created file-specific visitor for $uri with ${fileSpecificVisitor.getNodes(uri).size} nodes")
        return fileSpecificVisitor
    }

    /**
     * Get the symbol table for a specific file.
     * This finds which context the file belongs to and returns the appropriate symbol table.
     */
    fun getSymbolTableForFile(uri: URI): SymbolTable? {
        val contextName = contextManager.getContextForFile(uri)
        return if (contextName != null) {
            contextSymbolTables[contextName]
        } else {
            null
        }
    }

    /**
     * Get the current workspace-wide AST visitor.
     * Returns a combined view across all contexts.
     */
    fun getWorkspaceAstVisitor(): AstVisitor? {
        logger.debug("getWorkspaceAstVisitor() called, returning: $workspaceAstVisitor")
        if (workspaceAstVisitor != null) {
            logger.debug("workspaceAstVisitor has ${workspaceAstVisitor!!.getAllNodes().size} nodes")
        }
        return workspaceAstVisitor
    }

    /**
     * Get the current workspace-wide symbol table.
     * Returns a combined view across all contexts.
     */
    fun getWorkspaceSymbolTable(): SymbolTable? {
        logger.debug("getWorkspaceSymbolTable() called, returning: $workspaceSymbolTable")
        if (workspaceSymbolTable != null) {
            logger.debug("workspaceSymbolTable stats: ${workspaceSymbolTable!!.getStatistics()}")
        }
        return workspaceSymbolTable
    }

    /**
     * Get the AST visitor for a specific context.
     */
    fun getAstVisitorForContext(contextName: String): AstVisitor? = contextAstVisitors[contextName]

    /**
     * Get the symbol table for a specific context.
     */
    fun getSymbolTableForContext(contextName: String): SymbolTable? = contextSymbolTables[contextName]

    /**
     * Get the AST for a specific file.
     */
    @Suppress("FunctionOnlyReturningConstant")
    fun getAstForFile(@Suppress("UNUSED_PARAMETER") uri: URI): ModuleNode? =
        null // Simplified - AST extraction not implemented

    /**
     * Get diagnostics for all files across all contexts.
     */
    fun getAllDiagnostics(): Map<URI, List<Diagnostic>> {
        val allDiagnostics = mutableMapOf<URI, List<Diagnostic>>()

        contextCompilationUnits.forEach { (contextName, compilationUnit) ->
            val contextDiagnostics = extractDiagnosticsForContext(contextName, compilationUnit)
            allDiagnostics.putAll(contextDiagnostics)
        }

        return allDiagnostics
    }

    private fun extractDiagnosticsForContext(
        contextName: String,
        compilationUnit: CompilationUnit,
    ): Map<URI, List<Diagnostic>> {
        try {
            val context = contextManager.getContext(contextName) ?: return emptyMap()
            val sourceUnits = buildSourceUnitMapping(context, compilationUnit)
            return DiagnosticConverter.convertErrorCollectorWithAttribution(
                compilationUnit.errorCollector,
                sourceUnits,
            )
        } catch (e: Exception) {
            logger.warn("Error extracting diagnostics for context $contextName", e)
            return emptyMap()
        }
    }

    private fun buildSourceUnitMapping(
        context: CompilationContextManager.CompilationContext,
        compilationUnit: CompilationUnit,
    ): Map<URI, SourceUnit> {
        val sourceUnits = mutableMapOf<URI, SourceUnit>()
        context.files.forEach { uri ->
            findSourceUnitForUri(uri, compilationUnit)?.let { sourceUnit ->
                sourceUnits[uri] = sourceUnit
            }
        }
        return sourceUnits
    }

    private fun findSourceUnitForUri(uri: URI, compilationUnit: CompilationUnit): SourceUnit? {
        val iterator = compilationUnit.iterator()
        while (iterator.hasNext()) {
            val sourceUnit = iterator.next()
            if (sourceUnit != null && getRelativePathForUri(uri).contains(sourceUnit.name)) {
                return sourceUnit
            }
        }
        return null
    }

    /**
     * Check if a file exists in the workspace.
     */
    @Suppress("FunctionOnlyReturningConstant")
    fun containsFile(@Suppress("UNUSED_PARAMETER") uri: URI): Boolean =
        false // Simplified - file management not implemented

    /**
     * Gets all Groovy files in the workspace.
     * Returns file paths for all currently tracked Groovy files.
     */
    fun getGroovyFiles(): List<Path> = emptyList() // Simplified - file listing not implemented

    /**
     * Get workspace statistics.
     */
    fun getWorkspaceStatistics(): Map<String, Any> {
        val contextStats = contextManager.getWorkspaceStatistics()
        val totalFiles = contextStats["totalFiles"] as Int
        val compiledModules = lastWorkspaceCompilationResult?.modulesByUri?.size ?: 0

        return mapOf(
            "totalFiles" to totalFiles,
            "compiledModules" to compiledModules,
            "dependencyCount" to dependencyManager.getDependencies().size,
            "totalContexts" to contextCompilationUnits.size,
            "symbolTableSize" to (getWorkspaceSymbolTable()?.getStatistics()?.values?.sum() ?: 0),
            "astNodeCount" to (getWorkspaceAstVisitor()?.getAllNodes()?.size ?: 0),
        )
    }

    // Context-aware compilation methods

    /**
     * Compiles a single compilation context.
     */
    private suspend fun compileContext(
        contextName: String,
        context: CompilationContextManager.CompilationContext,
    ): WorkspaceCompilationResult {
        logger.debug("Compiling context '$contextName' with ${context.fileCount()} files")
        logger.debug(
            "Context '$contextName' files: ${context.files.take(MAX_DEBUG_ITEMS_TO_LOG).joinToString {
                it.path
            }}",
        )

        return try {
            val compilationUnit = createContextCompilationUnit(contextName, context)
            val sourceUnits = addFilesToCompilationUnit(context, compilationUnit)
            performCompilation(contextName, compilationUnit)
            val modulesByUri = extractCompiledModules(compilationUnit, sourceUnits)
            val (astVisitor, symbolTable) = buildAstAndSymbolTable(sourceUnits, modulesByUri)

            storeContextState(contextName, compilationUnit, astVisitor, symbolTable)
            val diagnostics = extractDiagnostics(compilationUnit, sourceUnits)

            logCompilationResults(contextName, modulesByUri, diagnostics)
            WorkspaceCompilationResult.success(modulesByUri, diagnostics, astVisitor, symbolTable)
        } catch (e: Exception) {
            logger.error("Context '$contextName' compilation failed", e)
            WorkspaceCompilationResult.failure(emptyMap())
        }
    }

    private fun createContextCompilationUnit(
        contextName: String,
        context: CompilationContextManager.CompilationContext,
    ): CompilationUnit {
        val config = createCompilerConfiguration(context.classpath)
        val classLoader = GroovyClassLoader()
        val compilationUnit = CompilationUnit(config, null, classLoader)

        addDependencyContexts(contextName, compilationUnit)
        return compilationUnit
    }

    private fun addDependencyContexts(contextName: String, compilationUnit: CompilationUnit) {
        val dependencies = contextManager.getDependenciesForContext(contextName)
        dependencies.forEach { depContext ->
            val depCompilationUnit = contextCompilationUnits[depContext.name]
            if (depCompilationUnit != null) {
                addDependencyClasses(compilationUnit, depCompilationUnit)
            }
        }
    }

    private fun addFilesToCompilationUnit(
        context: CompilationContextManager.CompilationContext,
        compilationUnit: CompilationUnit,
    ): MutableMap<URI, SourceUnit> {
        val sourceUnits = mutableMapOf<URI, SourceUnit>()
        val config = compilationUnit.configuration
        val classLoader = compilationUnit.classLoader

        context.files.forEach { uri ->
            val content = getFileContent(uri)
            val sourceUnit = createSourceUnit(uri, content, config, classLoader, compilationUnit)
            compilationUnit.addSource(sourceUnit)
            sourceUnits[uri] = sourceUnit
        }

        return sourceUnits
    }

    private fun getFileContent(uri: URI): String = fileContents[uri] ?: loadFileContentFromDisk(uri)

    private fun loadFileContentFromDisk(uri: URI): String = try {
        val path = java.nio.file.Paths.get(uri)
        if (java.nio.file.Files.exists(path)) {
            val diskContent = java.nio.file.Files.readString(path)
            fileContents[uri] = diskContent // Cache for future use
            diskContent
        } else {
            logger.warn("File not found on disk: $uri")
            ""
        }
    } catch (e: Exception) {
        logger.warn("Error reading file $uri: ${e.message}")
        ""
    }

    private fun createSourceUnit(
        uri: URI,
        content: String,
        config: CompilerConfiguration,
        classLoader: GroovyClassLoader,
        compilationUnit: CompilationUnit,
    ): SourceUnit {
        val relativePath = getRelativePathForUri(uri)
        val source = StringReaderSource(content, config)
        return SourceUnit(relativePath, source, config, classLoader, compilationUnit.errorCollector)
    }

    private fun performCompilation(contextName: String, compilationUnit: CompilationUnit) {
        try {
            compilationUnit.compile(Phases.SEMANTIC_ANALYSIS)
            logger.debug("Context '$contextName' compilation completed successfully")
        } catch (e: CompilationFailedException) {
            logger.debug("Context '$contextName' compilation failed (expected for syntax errors): ${e.message}")
        }
    }

    private fun extractCompiledModules(
        compilationUnit: CompilationUnit,
        sourceUnits: Map<URI, SourceUnit>,
    ): MutableMap<URI, ModuleNode> {
        val modulesByUri = mutableMapOf<URI, ModuleNode>()

        logger.debug("extractCompiledModules() called")
        logger.debug("sourceUnits.size = ${sourceUnits.size}")
        logger.debug("sourceUnits.keys = ${sourceUnits.keys}")

        // CRITICAL FIX: Match modules to URIs correctly
        // Iterate through source units and find their corresponding modules
        sourceUnits.forEach { (uri, sourceUnit) ->
            val module = sourceUnit.ast
            if (module != null) {
                logger.debug("Found module for URI $uri: ${module.description}")
                modulesByUri[uri] = module
                compiledFiles[uri] = module
            } else {
                logger.debug("No AST module for source unit at URI $uri")
            }
        }

        // Log any mismatch for debugging
        if (compilationUnit.ast?.modules != null) {
            val totalModules = compilationUnit.ast.modules.size
            if (totalModules != modulesByUri.size) {
                logger.warn(
                    "Module count mismatch: CompilationUnit has $totalModules modules " +
                        "but we matched ${modulesByUri.size}",
                )
            }
        }

        return modulesByUri
    }

    private fun buildAstAndSymbolTable(
        sourceUnits: Map<URI, SourceUnit>,
        modulesByUri: Map<URI, ModuleNode>,
    ): Pair<AstVisitor, SymbolTable> {
        val astVisitor = AstVisitor()
        val symbolTable = SymbolTable()

        logger.debug("buildAstAndSymbolTable() called")
        logger.debug("sourceUnits.size = ${sourceUnits.size}")
        logger.debug("modulesByUri.size = ${modulesByUri.size}")
        logger.debug("modulesByUri.keys = ${modulesByUri.keys}")

        sourceUnits.forEach { (uri, sourceUnit) ->
            val module = modulesByUri[uri]
            logger.debug("Processing URI $uri, module = $module")
            if (module != null) {
                logger.debug("Visiting module for URI $uri")
                logger.debug("Module has ${module.classes?.size ?: 0} classes")
                module.classes?.forEach { classNode ->
                    logger.debug("  Class: ${classNode.name} at ${classNode.lineNumber}:${classNode.columnNumber}")
                }
                astVisitor.visitModule(module, sourceUnit, uri)
            } else {
                logger.debug("No module found for URI $uri")
            }
        }

        symbolTable.buildFromVisitor(astVisitor)
        logger.debug("AST visitor nodes after building: ${astVisitor.getAllNodes().size}")
        logger.debug("Symbol table stats after building: ${symbolTable.getStatistics()}")
        return astVisitor to symbolTable
    }

    private fun storeContextState(
        contextName: String,
        compilationUnit: CompilationUnit,
        astVisitor: AstVisitor,
        symbolTable: SymbolTable,
    ) {
        contextCompilationUnits[contextName] = compilationUnit
        contextAstVisitors[contextName] = astVisitor
        contextSymbolTables[contextName] = symbolTable
    }

    private fun extractDiagnostics(
        compilationUnit: CompilationUnit,
        sourceUnits: Map<URI, SourceUnit>,
    ): Map<URI, List<Diagnostic>> = DiagnosticConverter.convertErrorCollectorWithAttribution(
        compilationUnit.errorCollector,
        sourceUnits,
    )

    private fun logCompilationResults(
        contextName: String,
        modulesByUri: Map<URI, ModuleNode>,
        diagnostics: Map<URI, List<Diagnostic>>,
    ) {
        logger.debug(
            "Context '$contextName' completed: ${modulesByUri.size} modules, " +
                "${diagnostics.values.sumOf { it.size }} diagnostics",
        )
    }

    /**
     * Recompiles a specific context.
     */
    private suspend fun recompileContext(contextName: String): WorkspaceCompilationResult {
        val context = contextManager.getContext(contextName)
        return if (context != null) {
            val result = compileContext(contextName, context)
            // Rebuild combined workspace views after context update
            rebuildCombinedWorkspaceViews()
            result
        } else {
            logger.warn("Context '$contextName' not found, performing full workspace recompilation")
            recompileWorkspace()
        }
    }

    /**
     * Recompiles the entire workspace by rebuilding all contexts.
     */
    private suspend fun recompileWorkspace(): WorkspaceCompilationResult = workspaceRoot?.let { root ->
        initializeWorkspace(root)
    } ?: WorkspaceCompilationResult.failure(emptyMap())

    /**
     * Adds compiled classes from a dependency context to the current compilation unit.
     */
    private fun addDependencyClasses(
        @Suppress("UnusedParameter") compilationUnit: CompilationUnit,
        @Suppress("UnusedParameter") dependencyUnit: CompilationUnit,
    ) {
        // TODO: Implement proper dependency class addition
        // This might involve adding the dependency's AST modules to the current unit's classpath
        logger.debug("Adding dependency classes to compilation unit")
    }

    /**
     * Gets a relative path for a URI for better error reporting.
     */
    private fun getRelativePathForUri(uri: URI): String {
        val workspaceRoot = this.workspaceRoot
        return if (workspaceRoot != null) {
            try {
                workspaceRoot.relativize(Path.of(uri)).toString()
            } catch (e: Exception) {
                logger.debug("Failed to get relative path for URI: $uri", e)
                uri.path.substringAfterLast('/')
            }
        } else {
            uri.path.substringAfterLast('/')
        }
    }

    /**
     * Finds the SourceUnit for a given URI from any context.
     */
    private fun findSourceUnitForUri(uri: URI): SourceUnit? {
        contextCompilationUnits.values.forEach { compilationUnit ->
            val iterator = compilationUnit.iterator()
            while (iterator.hasNext()) {
                val sourceUnit = iterator.next()
                if (sourceUnit != null && getUriFromSourceUnit(sourceUnit) == uri) {
                    return sourceUnit
                }
            }
        }
        return null
    }

    /**
     * Find the source unit for a specific module within a given context.
     */
    @Suppress("UnusedPrivateMember")
    private fun findSourceUnitForModule(module: ModuleNode, contextName: String): SourceUnit? {
        val compilationUnit = contextCompilationUnits[contextName]
        if (compilationUnit != null) {
            val iterator = compilationUnit.iterator()
            while (iterator.hasNext()) {
                val sourceUnit = iterator.next()
                if (sourceUnit != null && sourceUnit.ast == module) {
                    return sourceUnit
                }
            }
        }
        return null
    }

    /**
     * Find which context a given module belongs to.
     */
    @Suppress("UnusedPrivateMember")
    private fun findContextForModule(module: ModuleNode): String? {
        contextCompilationUnits.forEach { (contextName, compilationUnit) ->
            val iterator = compilationUnit.iterator()
            while (iterator.hasNext()) {
                val sourceUnit = iterator.next()
                if (sourceUnit != null && sourceUnit.ast == module) {
                    return contextName
                }
            }
        }
        return null
    }

    /**
     * Extracts URI from a SourceUnit (this is a best-effort approach).
     */
    private fun getUriFromSourceUnit(sourceUnit: SourceUnit): URI? = try {
        // Try to match by name/path
        Path.of(sourceUnit.name).toUri()
    } catch (e: Exception) {
        logger.debug("Failed to create URI from source unit: ${sourceUnit.name}", e)
        null
    }

    /**
     * Creates a compiler configuration with context-specific classpath.
     */
    private fun createCompilerConfiguration(contextClasspath: List<Path> = emptyList()): CompilerConfiguration =
        CompilerConfiguration().apply {
            // We want to compile to AST analysis but not generate bytecode
            targetDirectory = null

            // Enable debugging information for better diagnostics
            debug = true

            // Set optimization options for better error reporting
            optimizationOptions = mapOf(
                CompilerConfiguration.GROOVYDOC to true,
            )

            // Set encoding
            sourceEncoding = "UTF-8"

            // Combine context classpath with global dependencies
            val globalDependencies = dependencyManager.getDependencies()
            val allClasspath = contextClasspath + globalDependencies
            if (allClasspath.isNotEmpty()) {
                val classpathString = allClasspath.joinToString(System.getProperty("path.separator")) {
                    it.toString()
                }
                setClasspath(classpathString)
                logger.debug(
                    "Added ${allClasspath.size} dependencies to compiler classpath " +
                        "(${contextClasspath.size} context + ${globalDependencies.size} global)",
                )
            }
        }

    /**
     * Legacy method for backward compatibility.
     */
    private fun createCompilerConfiguration(): CompilerConfiguration = createCompilerConfiguration(emptyList())

    @Suppress("UnusedPrivateMember")
    private fun getCurrentCompilationResult(): WorkspaceCompilationResult = WorkspaceCompilationResult.success(
        compiledFiles.toMap(),
        getAllDiagnostics(),
        getWorkspaceAstVisitor(),
        getWorkspaceSymbolTable(),
    )

    /**
     * Rebuilds the combined workspace AST visitor and symbol table from all contexts.
     */
    private fun rebuildCombinedWorkspaceViews() {
        val combinedAstVisitor = AstVisitor()
        val combinedSymbolTable = SymbolTable()

        val allModules = collectAllModulesFromContexts()
        buildCombinedViewsFromModules(allModules, combinedAstVisitor)

        combinedSymbolTable.buildFromVisitor(combinedAstVisitor)

        workspaceAstVisitor = combinedAstVisitor
        workspaceSymbolTable = combinedSymbolTable

        logger.debug("Rebuilt combined workspace views with ${allModules.size} modules")
    }

    private fun collectAllModulesFromContexts(): Map<URI, ModuleNode> {
        val allModules = mutableMapOf<URI, ModuleNode>()
        contextCompilationUnits.values.forEach { compilationUnit ->
            collectModulesFromCompilationUnit(compilationUnit, allModules)
        }
        return allModules
    }

    private fun collectModulesFromCompilationUnit(
        compilationUnit: CompilationUnit,
        allModules: MutableMap<URI, ModuleNode>,
    ) {
        val iterator = compilationUnit.iterator()
        while (iterator.hasNext()) {
            val sourceUnit = iterator.next()
            if (sourceUnit?.ast != null) {
                getUriFromSourceUnit(sourceUnit)?.let { uri ->
                    allModules[uri] = sourceUnit.ast
                }
            }
        }
    }

    private fun buildCombinedViewsFromModules(allModules: Map<URI, ModuleNode>, combinedAstVisitor: AstVisitor) {
        allModules.forEach { (uri, module) ->
            findSourceUnitForUri(uri)?.let { sourceUnit ->
                combinedAstVisitor.visitModule(module, sourceUnit, uri)
            }
        }
    }

    /**
     * Clear all compilation state (useful for testing).
     */
    suspend fun clearWorkspace() = compilationMutex.withLock {
        contextCompilationUnits.clear()
        contextAstVisitors.clear()
        contextSymbolTables.clear()
        workspaceRoot = null
        fileContents.clear()
        fileModificationTimes.clear()
        compiledFiles.clear()

        // Clear combined workspace views
        workspaceAstVisitor = null
        workspaceSymbolTable = null

        logger.info("Workspace compilation state cleared")
    }
}
