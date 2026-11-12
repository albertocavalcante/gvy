// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

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

    /**
     * The original source text that was compiled.
     */
    val sourceText: String? = null,

    /**
     * Whether the parser ran to completion over [sourceText]. False for a result built from an exception (a
     * worker crash, an IO error): that says nothing about the text, so it must not be remembered as
     * unparseable (#1344).
     */
    val parseCompleted: Boolean = false,
) {
    companion object {
        /**
         * Creates a successful compilation result.
         */
        fun success(ast: ASTNode, diagnostics: List<Diagnostic> = emptyList(), sourceText: String? = null) =
            CompilationResult(true, ast, diagnostics, sourceText, parseCompleted = true)

        /**
         * Creates a failed compilation result.
         */
        fun failure(diagnostics: List<Diagnostic>, sourceText: String? = null) =
            CompilationResult(false, null, diagnostics, sourceText)
    }
}
