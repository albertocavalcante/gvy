// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.nativeapi

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.SourceUnit

/**
 * Output of a parser invocation.
 */
data class ParseResult(
    val ast: ModuleNode?,
    val compilationUnit: CompilationUnit,
    val sourceUnit: SourceUnit,
    val diagnostics: List<ParserDiagnostic>,
    val symbolTable: SymbolTable,
    val astModel: GroovyAstModel,
    /**
     * Token classification index for determining whether a given offset is within a comment,
     * string literal, or GString.
     *
     * May be `null` for backward compatibility when token classification information
     * is not available or not required by the caller.
     */
    val tokenIndex: com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex? = null,
    /** What the parser gave up to produce this result; undegraded unless stated otherwise. */
    val outcome: ParseOutcome = ParseOutcome(),
    /** Immutable source annotation evidence from this compilation, before rewriting or policy stripping. */
    val sourceAnnotations: SourceAnnotationIndex = SourceAnnotationIndex.UNAVAILABLE,
    /** Exact ordered inputs used for this compile. Null for older or synthetic results without that evidence. */
    val classpathInputs: NativeClasspathInputs? = null,
) {
    val isSuccessful: Boolean = diagnostics.none { it.severity == ParserSeverity.ERROR }
}
