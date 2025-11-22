package com.github.albertocavalcante.groovylsp.types

import com.github.albertocavalcante.groovylsp.compilation.CompilationContext
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.eclipse.lsp4j.Location

/**
 * Interface for resolving type information from AST nodes.
 * Inspired by IntelliJ's type resolution patterns and the fork-groovy-language-server approach.
 */
interface TypeResolver {

    /**
     * Resolves the type of an AST node.
     *
     * @param node The AST node to resolve the type for
     * @param context The compilation context containing necessary information for resolution
     * @return The resolved ClassNode representing the type, or null if cannot be resolved
     */
    suspend fun resolveType(node: ASTNode, context: CompilationContext): ClassNode?

    /**
     * Resolves the location where a type is defined.
     *
     * @param node The AST node whose type definition location should be found
     * @param context The compilation context
     * @return The LSP Location of the type definition, or null if not found
     */
    suspend fun resolveTypeDefinition(node: ASTNode, context: CompilationContext): Location?

    /**
     * Resolves a ClassNode to its definition location.
     *
     * @param classNode The ClassNode to find the location for
     * @param context The compilation context
     * @return The LSP Location where this class is defined, or null if not found
     */
    suspend fun resolveClassLocation(classNode: ClassNode, context: CompilationContext): Location?
}
