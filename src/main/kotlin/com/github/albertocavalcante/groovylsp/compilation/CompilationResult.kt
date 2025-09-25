package com.github.albertocavalcante.groovylsp.compilation

import org.codehaus.groovy.ast.ASTNode
import org.eclipse.lsp4j.Diagnostic

/**
 * Result of compiling a Groovy source file.
 */
data class CompilationResult(
    /**
     * Whether the compilation was successful (no errors).
     */
    val isSuccess: Boolean,

    /**
     * The parsed AST if compilation was successful, null otherwise.
     */
    val ast: ASTNode?,

    /**
     * List of diagnostics (errors, warnings, etc.) from compilation.
     */
    val diagnostics: List<Diagnostic>,
) {
    companion object {
        /**
         * Creates a successful compilation result.
         */
        fun success(ast: ASTNode, diagnostics: List<Diagnostic> = emptyList()) =
            CompilationResult(true, ast, diagnostics)

        /**
         * Creates a failed compilation result.
         */
        fun failure(diagnostics: List<Diagnostic>) = CompilationResult(false, null, diagnostics)
    }
}
