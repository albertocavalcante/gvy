package com.github.albertocavalcante.gvy.gls.providers.inlayhints

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import com.github.albertocavalcante.groovyparser.ast.SymbolTable
import com.github.albertocavalcante.groovyparser.ast.symbols.Symbol
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.InlayHintsConfiguration
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
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
) {
    /**
     * [workspaceSymbols]' methods keyed by method name and owner simple name, built once per request on
     * first use. Each call then reads only the methods it could match instead of re-scanning every
     * workspace symbol (#1312); common names such as `get` stay cheap because the owner narrows them.
     */
    val workspaceMethods: Map<WorkspaceMethodKey, List<Symbol.Method>> by lazy {
        workspaceSymbols.asSequence().filterIsInstance<Symbol.Method>()
            .mapNotNull { method ->
                method.owner?.let { WorkspaceMethodKey(method.name, it.nameWithoutPackage) to method }
            }
            .groupBy({ it.first }, { it.second })
    }

    /**
     * Whether [typeName] is a full class name to match exactly (#1374). A qualified name always is. A bare
     * name is the full name of a default-package class, but only default-package code can use one, so in
     * packaged (or unknown) code a bare name stays a simple name.
     */
    fun namesExactly(typeName: String): Boolean =
        typeName.contains('.') || (moduleNode != null && moduleNode.packageName.isNullOrBlank())
}

/** A workspace method lookup key: the method's name and its declaring class's simple name. */
data class WorkspaceMethodKey(val name: String, val ownerSimpleName: String)
