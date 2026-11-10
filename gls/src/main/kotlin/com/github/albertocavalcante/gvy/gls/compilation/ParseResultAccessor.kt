package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.gvy.gls.worker.WorkerSessionManager
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.Diagnostic
import java.net.URI
import java.nio.file.Path

private const val CONTENT_START_PREVIEW_LENGTH = 50
private const val CONTENT_PREVIEW_LENGTH = 100

/**
 * Service for providing safe, validated access to cached parse results and AST components.
 *
 * This service provides a clean API for accessing compilation results and their components
 * (AST, diagnostics, symbol tables, etc.). It also handles the detection and re-compilation
 * of "suspicious" Script nodes that may occur when files are compiled before workspace
 * initialization is complete.
 *
 * Thread-safe: Stateless service, delegates thread-safety to dependencies.
 *
 * @param cacheService Service managing compilation result caching
 * @param workerSessionManager Manager for parser worker sessions (needed for re-compilation)
 * @param workspaceManager Workspace configuration provider (needed for re-compilation)
 * @param symbolIndexer Symbol index kept in step with any result this accessor re-caches
 */
class ParseResultAccessor(
    private val cacheService: CompilationCacheService,
    private val workerSessionManager: WorkerSessionManager,
    private val workspaceManager: WorkspaceManager,
    private val symbolIndexer: SymbolIndexingService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Gets the cached ParseResult for a URI, compiled under the current workspace configuration.
     * Returns null if not cached or compilation failed. The entry of a closed file is retired whenever its
     * buffer closes or the file changes on disk, so this never serves a closed buffer's parse.
     *
     * @param uri The URI of the file
     * @return ParseResult if cached, null otherwise
     */
    fun getParseResult(uri: URI): ParseResult? =
        cacheService.getCachedWithContent(uri, workspaceManager.fingerprintFor(uri))?.second

    /**
     * Gets the AST (ModuleNode) for a URI.
     * Returns null if not cached or compilation failed.
     *
     * @param uri The URI of the file
     * @return ASTNode if available, null otherwise
     */
    fun getAst(uri: URI): ASTNode? = getParseResult(uri)?.ast

    /**
     * Gets diagnostics (errors/warnings) for a URI.
     * Returns empty list if not cached or no diagnostics.
     *
     * @param uri The URI of the file
     * @return List of LSP Diagnostic objects
     */
    fun getDiagnostics(uri: URI): List<Diagnostic> =
        getParseResult(uri)?.diagnostics?.map { it.toLspDiagnostic() } ?: emptyList()

    /**
     * Gets the AST model for traversing the syntax tree.
     * Returns null if not cached or compilation failed.
     *
     * @param uri The URI of the file
     * @return GroovyAstModel if available, null otherwise
     */
    fun getAstModel(uri: URI): GroovyAstModel? = getParseResult(uri)?.astModel

    /**
     * Gets the symbol table for name resolution.
     * Returns null if not cached or compilation failed.
     *
     * @param uri The URI of the file
     * @return SymbolTable if available, null otherwise
     */
    fun getSymbolTable(uri: URI): SymbolTable? = getParseResult(uri)?.symbolTable

    /**
     * Gets the token index for position-based lookups.
     * Returns null if not cached or compilation failed.
     *
     * @param uri The URI of the file
     * @return TokenIndex if available, null otherwise
     */
    fun getTokenIndex(uri: URI) = getParseResult(uri)?.tokenIndex

    /**
     * Checks if a parse result contains a suspicious Script node.
     *
     * A "suspicious" Script node is one where:
     * - There's exactly one class in the AST
     * - The class extends groovy.lang.Script
     * - The class name matches the filename
     *
     * This typically happens when a file is compiled before sourceRoots were populated,
     * causing the Groovy compiler to fall back to treating it as a Script.
     *
     * @param uri The URI of the file
     * @param parseResult The parse result to check
     * @return true if suspicious Script detected, false otherwise
     */
    fun isSuspiciousScript(uri: URI, parseResult: ParseResult): Boolean {
        val filename = runCatching { Path.of(uri).fileName.toString().substringBeforeLast(".") }.getOrNull()
            ?: return false
        val classes = parseResult.ast?.classes ?: return false

        if (classes.size != 1) {
            return false
        }
        val singleClass = classes.single()
        // Use safe call for superClass - it's null for interfaces
        return singleClass.superClass?.name == "groovy.lang.Script" && singleClass.name == filename
    }

    /**
     * Gets a valid parse result for the URI, ensuring stale Script nodes are recompiled.
     *
     * Unlike [getParseResult], this method checks if the cached result contains a suspicious
     * Script node (which can happen when file was compiled before sourceRoots was populated).
     * If detected, it re-parses the entry's own source text directly at CONVERSION phase WITHOUT
     * workspaceSources to avoid name collisions that cause Script fallback.
     *
     * Use this method when you need to ensure the AST accurately represents the source code
     * structure (e.g., test discovery, Spock detection).
     *
     * @param uri The URI of the file
     * @return ParseResult if available, null if not cached or re-compilation failed
     */
    suspend fun getValidParseResult(uri: URI): ParseResult? {
        // Owned before the cache and disk are read: an invalidation meanwhile discards the re-parse below.
        val ticket = cacheService.ownership.begin(uri)
        val workspace = workspaceManager.current()
        val fingerprint = workspaceManager.fingerprintFor(uri, workspace)
        val (cachedContent, cachedResult) = cacheService.getCachedWithContent(uri, fingerprint) ?: return null

        // Use helper function to check for suspicious Script node
        if (isSuspiciousScript(uri, cachedResult)) {
            logger.info { "Cached result has suspicious Script node for $uri, parsing directly at CONVERSION phase" }
            // Re-parse the text this entry was compiled from. Reading the disk instead would publish the file
            // over an open, unsaved buffer that the entry describes.
            val content = cachedContent
            val sourcePath = runCatching { Path.of(uri) }.getOrNull()
            logger.info {
                "Content starts with: '${content.take(CONTENT_START_PREVIEW_LENGTH).replace("\n", "\\n")}'"
            }
            val parseResult = workerSessionManager.parse(
                ParseRequest(
                    uri = uri,
                    content = content,
                    classpath = workspaceManager.getClasspathForFile(uri, content, workspace),
                    // Don't add source roots - prevents classloader from finding .groovy file
                    sourceRoots = emptyList(),
                    // Don't add other sources - causes Script fallback
                    workspaceSources = emptyList(),
                    locatorCandidates = buildLocatorCandidates(uri, sourcePath),
                    compilePhase = Phases.CONVERSION,
                ),
            )

            // Replace the cached result and its symbol index together, unless they were retired meanwhile
            if (parseResult.ast != null) {
                val symbols = symbolIndexer.prepare(content, parseResult.astModel)
                cacheService.publish(ticket, content, parseResult, fingerprint) { symbolIndexer.install(uri, symbols) }
            }
            logger.info {
                "Re-parsed $uri at CONVERSION: classes=${
                    parseResult.ast?.classes?.map {
                        "${it.name} (super=${it.superClass?.name ?: "null"})"
                    }
                }"
            }
            logger.debug {
                "Content preview: ${content.take(CONTENT_PREVIEW_LENGTH).replace("\n", "\\n")}"
            }
            return parseResult
        }

        return cachedResult
    }

    private fun buildLocatorCandidates(uri: URI, sourcePath: Path?): Set<String> {
        val candidates = mutableSetOf<String>()
        candidates += uri.toString()
        candidates += uri.path
        sourcePath?.let { path ->
            candidates += path.toString()
            candidates += path.toAbsolutePath().toString()
        }
        return candidates
    }
}
