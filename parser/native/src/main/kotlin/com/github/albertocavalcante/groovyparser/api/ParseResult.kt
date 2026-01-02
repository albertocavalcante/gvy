package com.github.albertocavalcante.groovyparser.api

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
) {
    val isSuccessful: Boolean = diagnostics.none { it.severity == ParserSeverity.ERROR }
}
