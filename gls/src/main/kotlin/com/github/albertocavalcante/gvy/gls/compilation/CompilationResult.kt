// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.nativeapi.SourceAnnotationIndex
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ModuleNode
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
    /** Captured with [ast] and [sourceText]; never reacquired from a newer URI cache entry. */
    val sourceAnnotations: SourceAnnotationIndex =
        (ast as? ModuleNode)?.let(SourceAnnotationIndex::fromModule) ?: SourceAnnotationIndex.UNAVAILABLE,
    /** Captured with [sourceText], so requests do not reacquire symbols from a later or cleared cache entry. */
    val astModel: GroovyAstModel? = null,
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
