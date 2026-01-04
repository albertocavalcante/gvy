package com.github.albertocavalcante.gvy.semantics.native

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeLub
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculatorRegistry
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.Expression

/**
 * Main entry point for semantic analysis of Groovy code.
 *
 * Inspired by rust-analyzer's Semantics and JavaParser's JavaSymbolSolver.
 *
 * Usage:
 * ```kotlin
 * val semantics = GroovySemantics(typeSolver)
 * semantics.inject(moduleNode)
 *
 * val type = semantics.resolveType(expression)
 * ```
 */
class GroovySemantics(
    private val typeSolver: TypeSolver,
    private val calculatorRegistry: TypeCalculatorRegistry = NativeCalculators.createRegistry(),
) {
    // Cache of contexts per module
    private val contextCache = mutableMapOf<ModuleNode, NativeTypeContext>()

    /**
     * Inject semantics into a parsed module.
     * After injection, semantic operations are available.
     */
    fun inject(module: ModuleNode) {
        if (module !in contextCache) {
            val scope = buildRootScope(module)
            val context = NativeTypeContext(
                typeSolver = typeSolver,
                calculatorRegistry = calculatorRegistry,
                scope = scope,
                isStaticCompilation = hasCompileStatic(module),
            )

            // Populate script variables from top-level declarations
            module.statementBlock?.statements?.forEach { stmt ->
                if (stmt is org.codehaus.groovy.ast.stmt.ExpressionStatement &&
                    stmt.expression is org.codehaus.groovy.ast.expr.DeclarationExpression
                ) {
                    val decl = stmt.expression as org.codehaus.groovy.ast.expr.DeclarationExpression
                    val name = decl.variableExpression.name
                    val type = calculatorRegistry.calculate(decl, context)
                    scope.defineVariable(name, type)
                }
            }

            contextCache[module] = context
        }
    }

    /**
     * Resolve the type of an AST node.
     */
    fun resolveType(node: ASTNode): SemanticType {
        val context = findContext(node)
            ?: return SemanticType.Unknown("Node not in injected module")
        return calculatorRegistry.calculate(node, context)
    }

    /**
     * Resolve the type of an expression.
     */
    fun resolveType(expression: Expression): SemanticType = resolveType(expression as ASTNode)

    /**
     * Check if a type is assignable to another.
     */
    fun isAssignableTo(from: SemanticType, to: SemanticType): Boolean {
        // Basic implementation - can be extended
        if (from == to) return true
        if (to == SemanticType.Dynamic()) return true
        if (from is SemanticType.Null && to is SemanticType.Known) return true
        // TODO: Add more assignability rules
        return false
    }

    /**
     * Compute least upper bound of types.
     */
    fun lub(types: List<SemanticType>): SemanticType {
        // Assuming TypeLub is available via import
        return TypeLub.lub(types)
    }

    private fun buildRootScope(module: ModuleNode): NativeScope {
        // Start with empty scope or first class's scope
        var scope = NativeScope.empty()

        for (classNode in module.classes) {
            // For now we just use the first class found as the root scope basis
            // (common for scripts where it's the script class)
            scope = NativeScope.fromClass(classNode)
            break
        }

        return scope
    }

    private fun hasCompileStatic(module: ModuleNode): Boolean {
        // Check for @CompileStatic annotation on classes
        return module.classes.any { cls ->
            cls.annotations.any {
                it.classNode.name.endsWith("CompileStatic")
            }
        }
    }

    private fun findContext(@Suppress("UNUSED_PARAMETER") node: ASTNode): NativeTypeContext? {
        // Find which module this node belongs to
        // TODO: Walk up using parent logic if we had it.
        // For now, we assume single module injection and return the first one.
        return contextCache.values.firstOrNull()
    }
}
