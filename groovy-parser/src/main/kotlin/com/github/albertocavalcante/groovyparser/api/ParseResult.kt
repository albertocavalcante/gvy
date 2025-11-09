package com.github.albertocavalcante.groovyparser.api

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
) {
    val isSuccessful: Boolean = diagnostics.none { it.severity == ParserSeverity.ERROR }
}
