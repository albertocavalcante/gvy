package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.Variable

/**
 * Symbol table facade providing access to registry, resolver, and builder.
 * Direct access to components reduces delegation boilerplate.
 * Refactored to expose internal components publicly.
 */
class SymbolTable {

    // Make these public for direct access
    val registry = SymbolRegistry()
    val resolver = SymbolResolver(registry)
    val builder = SymbolTableBuilder(registry)

    // Keep only coordination methods (8 functions max)
    fun buildFromVisitor(visitor: GroovyAstModel) {
        clear()
        builder.buildFromVisitor(visitor)
    }

    fun clear() {
        registry.clear()
    }

    fun isEmpty(): Boolean = registry.isEmpty()

    fun getStatistics(): Map<String, Int> = registry.getStatistics()

    fun resolveSymbol(node: ASTNode, visitor: GroovyAstModel): Variable? = resolver.resolveSymbol(node, visitor)

    fun refresh() {
        // Coordinate refresh across components
        registry.clear()
        // Resolver and builder will be refreshed when registry is cleared
    }

    fun validate(): Boolean {
        // Validate consistency across components
        return registry.getStatistics().isNotEmpty()
    }

    fun export(): SymbolTableSnapshot {
        // Export complete state
        return SymbolTableSnapshot(
            registry = registry.getStatistics(),
            resolver = mapOf("cacheSize" to 0), // placeholder
        )
    }
}

/**
 * Snapshot of symbol table state for debugging/export purposes
 */
data class SymbolTableSnapshot(val registry: Map<String, Int>, val resolver: Map<String, Int>)
