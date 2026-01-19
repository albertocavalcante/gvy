package com.github.albertocavalcante.gvy.diagnostics.api

import org.codehaus.groovy.ast.ModuleNode
import java.net.URI

/**
 * Provides access to cached AST from the compilation service.
 *
 * This interface allows diagnostic providers to access the already-parsed AST
 * without triggering additional parsing. The AST is cached by the LSP's
 * compilation service and shared across features (completion, hover, diagnostics).
 *
 * @see CodeNarcDiagnosticProvider for usage in AST-aware diagnostic positioning
 */
fun interface CompilationAccessor {
    /**
     * Retrieves the cached ModuleNode (Groovy AST) for the given URI.
     *
     * @param uri The URI of the source file
     * @return The cached ModuleNode, or null if not available (not yet parsed,
     *         parse failed, or URI not in workspace)
     */
    fun getAst(uri: URI): ModuleNode?
}
