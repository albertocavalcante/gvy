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
    private val contextManager: CompilationContextManager = CompilationContextManager(),
) {

    // Helper components removed - references to non-existent classes

    private val logger = LoggerFactory.getLogger(WorkspaceCompilationService::class.java)
    private val compilationMutex = Mutex()

    // Context-aware compilation state
    private val contextCompilationUnits = ConcurrentHashMap<String, CompilationUnit>()
    private val contextAstVisitors = ConcurrentHashMap<String, AstVisitor>()
    private val contextSymbolTables = ConcurrentHashMap<String, SymbolTable>()
    private var workspaceRoot: Path? = null

    // Combined workspace-level views
    private var workspaceAstVisitor: AstVisitor? = null
    private var workspaceSymbolTable: SymbolTable? = null

    // File content and tracking
    private val fileContents = ConcurrentHashMap<URI, String>()
    private val fileModificationTimes = ConcurrentHashMap<URI, Long>()
    private val compiledFiles = ConcurrentHashMap<URI, ModuleNode>()

    // Legacy state for backward compatibility - delegated to helpers

    // Dependency management
    private val dependencyClasspath = mutableListOf<Path>()

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
                return WorkspaceCompilationResult.success(emptyMap(), emptyMap(), null, null)
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

            // Simply re-visit all modules from allModules map, using the correct context for each
            allModules.forEach { (uri, module) ->
                // Find which context this module belongs to
                val contextName = findContextForModule(module)
                if (contextName != null) {
                    val sourceUnit = findSourceUnitForModule(module, contextName)
                    if (sourceUnit != null) {
                        combinedAstVisitor.visitModule(module, sourceUnit, uri)
                    }
                }
            }

            // Build symbol table from the combined AST visitor
            combinedSymbolTable.buildFromVisitor(combinedAstVisitor)

            // Store the combined workspace views for persistent access
            workspaceAstVisitor = combinedAstVisitor
            workspaceSymbolTable = combinedSymbolTable

            logger.info(
                "Workspace compilation completed: ${allModules.size} modules, ${allDiagnostics.values.sumOf {
                    it.size
                }} diagnostics",
            )

            WorkspaceCompilationResult.success(allModules, allDiagnostics, combinedAstVisitor, combinedSymbolTable)
        } catch (e: Exception) {
            logger.error("Failed to initialize workspace compilation", e)
            WorkspaceCompilationResult.failure(emptyMap())
        }
    }

    /**
     * Update a single file and recompile its context incrementally.
     */
    suspend fun updateFile(uri: URI, content: String): WorkspaceCompilationResult = compilationMutex.withLock {
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
     * Update dependency classpath and recompile.
     */
    suspend fun updateDependencies(newDependencies: List<Path>): WorkspaceCompilationResult {
        if (newDependencies.toSet() != dependencyClasspath.toSet()) {
            dependencyClasspath.clear()
            dependencyClasspath.addAll(newDependencies)
            logger.info("Updated dependency classpath with ${dependencyClasspath.size} dependencies")

            return compilationMutex.withLock {
                recompileWorkspace()
            }
        }
        return getCurrentCompilationResult()
    }

    /**
     * Get the AST visitor for a specific file.
     * This finds which context the file belongs to and returns the appropriate AST visitor.
     */
    fun getAstVisitorForFile(uri: URI): AstVisitor? {
        val contextName = contextManager.getContextForFile(uri)
        return if (contextName != null) {
            contextAstVisitors[contextName]
        } else {
            null
        }
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
    fun getAstForFile(uri: URI): ModuleNode? = null // Simplified - AST extraction not implemented

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
    fun containsFile(uri: URI): Boolean = false // Simplified - file management not implemented

    /**
     * Gets all Groovy files in the workspace.
     * Returns file paths for all currently tracked Groovy files.
     */
    fun getGroovyFiles(): List<Path> = emptyList() // Simplified - file listing not implemented

    /**
     * Get workspace statistics.
     */
    fun getWorkspaceStatistics(): Map<String, Any> {
        val fileStats = mapOf("totalFiles" to 0)
        val moduleStats = mapOf("compiledModules" to 0)
        return mapOf(
            "totalFiles" to fileStats["totalFiles"]!!,
            "compiledModules" to moduleStats["compiledModules"]!!,
            "dependencyCount" to dependencyClasspath.size,
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
            compilationUnit.compile(Phases.CANONICALIZATION)
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
        logger.debug("compilationUnit.ast = ${compilationUnit.ast}")
        logger.debug("compilationUnit.ast?.modules?.size = ${compilationUnit.ast?.modules?.size}")
        logger.debug("sourceUnits.size = ${sourceUnits.size}")
        logger.debug("sourceUnits.keys = ${sourceUnits.keys}")

        compilationUnit.ast?.modules?.forEach { module ->
            logger.debug("Processing module: ${module.description}")
            val matchingUri = sourceUnits.entries.find { (_, sourceUnit) ->
                val matches = sourceUnit.ast == module
                logger.debug("Checking sourceUnit.ast ${sourceUnit.ast} == module $module: $matches")
                matches
            }?.key

            if (matchingUri != null) {
                logger.debug("Found matching URI for module: $matchingUri")
                modulesByUri[matchingUri] = module
                compiledFiles[matchingUri] = module
            } else {
                logger.debug("No matching URI found for module: ${module.description}")
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
            val allClasspath = contextClasspath + dependencyClasspath
            if (allClasspath.isNotEmpty()) {
                val classpathString = allClasspath.joinToString(System.getProperty("path.separator")) {
                    it.toString()
                }
                setClasspath(classpathString)
                logger.debug(
                    "Added ${allClasspath.size} dependencies to compiler classpath " +
                        "(${contextClasspath.size} context + ${dependencyClasspath.size} global)",
                )
            }
        }

    /**
     * Legacy method for backward compatibility.
     */
    private fun createCompilerConfiguration(): CompilerConfiguration = createCompilerConfiguration(emptyList())

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
