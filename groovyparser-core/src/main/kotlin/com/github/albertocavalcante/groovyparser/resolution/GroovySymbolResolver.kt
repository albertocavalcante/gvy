package com.github.albertocavalcante.groovyparser.resolution

import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.groovyparser.ast.expr.Expression
import com.github.albertocavalcante.groovyparser.resolution.contexts.ClassContext
import com.github.albertocavalcante.groovyparser.resolution.contexts.CompilationUnitContext
import com.github.albertocavalcante.groovyparser.resolution.contexts.MethodContext
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedMethodDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedValueDeclaration
import com.github.albertocavalcante.groovyparser.resolution.groovymodel.GroovyParserClassDeclaration
import com.github.albertocavalcante.groovyparser.resolution.groovymodel.GroovyParserTypeResolver
import com.github.albertocavalcante.groovyparser.resolution.model.SymbolReference
import com.github.albertocavalcante.groovyparser.resolution.typeinference.TypeExtractor
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType

/**
 * Main entry point for symbol resolution and type inference.
 *
 * This class provides methods to:
 * - Resolve the type of expressions
 * - Look up symbols (variables, fields, parameters)
 * - Resolve method calls
 *
 * @param typeSolver The type solver to use for resolving type names
 */
class GroovySymbolResolver(private val typeSolver: TypeSolver) {

    /**
     * Resolves the type of an AST node.
     *
     * @param node The node to resolve the type of
     * @return The resolved type
     */
    fun resolveType(node: Node): ResolvedType {
        val context = createContext(node)
        val extractor = TypeExtractor(typeSolver, context)

        return when (node) {
            is Expression -> extractor.extractType(node)
            is FieldDeclaration -> GroovyParserTypeResolver.resolveType(node.type, typeSolver)
            is MethodDeclaration -> GroovyParserTypeResolver.resolveType(node.returnType, typeSolver)
            is Parameter -> GroovyParserTypeResolver.resolveType(node.type, typeSolver)
            else -> throw IllegalArgumentException("Cannot resolve type for ${node::class.simpleName}")
        }
    }

    /**
     * Resolves a symbol by name at a given location in the AST.
     *
     * @param name The name of the symbol to resolve
     * @param node The AST node providing context for resolution
     * @return A symbol reference that is either solved or unsolved
     */
    fun solveSymbol(name: String, node: Node): SymbolReference<ResolvedValueDeclaration> {
        val context = createContext(node)
        return context.solveSymbol(name)
    }

    /**
     * Resolves a type by name at a given location in the AST.
     *
     * @param name The name of the type to resolve
     * @param node The AST node providing context for resolution
     * @return A symbol reference that is either solved or unsolved
     */
    fun solveType(name: String, node: Node): SymbolReference<ResolvedTypeDeclaration> {
        val context = createContext(node)
        return context.solveType(name)
    }

    /**
     * Resolves a method by name and argument types at a given location.
     *
     * @param name The method name
     * @param argumentTypes The types of the arguments
     * @param node The AST node providing context for resolution
     * @return A symbol reference that is either solved or unsolved
     */
    fun solveMethod(
        name: String,
        argumentTypes: List<ResolvedType>,
        node: Node,
    ): SymbolReference<ResolvedMethodDeclaration> {
        val context = createContext(node)
        return context.solveMethod(name, argumentTypes)
    }

    /**
     * Creates a resolution context for the given AST node.
     */
    private fun createContext(node: Node): Context {
        // Walk up to find compilation unit
        val cu = findCompilationUnit(node) ?: throw IllegalArgumentException("Node has no CompilationUnit parent")
        val cuContext = CompilationUnitContext(cu, typeSolver)

        // Find enclosing class
        val classDecl = findEnclosingClass(node)
        if (classDecl == null) {
            return cuContext
        }

        val resolvedClass = GroovyParserClassDeclaration(classDecl, cu, typeSolver)
        val classContext = ClassContext(classDecl, resolvedClass, cuContext, typeSolver)

        // Find enclosing method
        val methodDecl = findEnclosingMethod(node)
        if (methodDecl == null) {
            return classContext
        }

        return MethodContext(methodDecl, classContext, typeSolver)
    }

    private fun findCompilationUnit(node: Node): CompilationUnit? {
        var current: Node? = node
        while (current != null) {
            if (current is CompilationUnit) return current
            current = current.parentNode
        }
        return null
    }

    private fun findEnclosingClass(node: Node): ClassDeclaration? {
        var current: Node? = node
        while (current != null) {
            if (current is ClassDeclaration) return current
            current = current.parentNode
        }
        return null
    }

    private fun findEnclosingMethod(node: Node): MethodDeclaration? {
        var current: Node? = node
        while (current != null) {
            if (current is MethodDeclaration) return current
            current = current.parentNode
        }
        return null
    }
}
