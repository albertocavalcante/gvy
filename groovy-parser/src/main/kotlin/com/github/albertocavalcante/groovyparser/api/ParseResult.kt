package com.github.albertocavalcante.groovyparser.api

import com.github.albertocavalcante.groovyparser.ast.AstVisitor
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.visitor.RecursiveAstVisitor
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
    val astVisitor: AstVisitor,
    val recursiveVisitor: RecursiveAstVisitor? = null,
) {
    val isSuccessful: Boolean = diagnostics.none { it.severity == ParserSeverity.ERROR }
}
