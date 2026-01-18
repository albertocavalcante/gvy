package com.github.albertocavalcante.groovylsp.providers.inlayhints

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.config.InlayHintsConfiguration
import com.github.albertocavalcante.groovylsp.types.SemanticTypeResolver
import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import io.github.oshai.kotlinlogging.KLogger
import org.codehaus.groovy.ast.ModuleNode

/**
 * Context for processing AST nodes during inlay hint generation.
 *
 * This class encapsulates all the dependencies and configuration needed
 * by hint strategies to generate inlay hints.
 *
 * @property astModel The AST model for the current file
 * @property moduleNode The module node for the current file (may be null)
 * @property symbolTable The symbol table for the current file (may be null)
 * @property workspaceSymbols All symbols from the workspace
 * @property compilationService The compilation service for resolving types
 * @property semanticResolver The semantic type resolver
 * @property config The inlay hints configuration
 * @property logger The logger for diagnostic messages
 */
data class HintContext(
    val astModel: GroovyAstModel,
    val moduleNode: ModuleNode?,
    val symbolTable: SymbolTable?,
    val workspaceSymbols: List<Symbol>,
    val compilationService: GroovyCompilationService,
    val semanticResolver: SemanticTypeResolver,
    val config: InlayHintsConfiguration,
    val logger: KLogger,
)
