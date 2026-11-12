package com.github.albertocavalcante.gvy.semantics.native

import arrow.core.left
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.TypeLub
import com.github.albertocavalcante.gvy.semantics.calculator.TypeCalculatorRegistry
import com.github.albertocavalcante.gvy.semantics.calculator.TypeInferenceError
import com.github.albertocavalcante.gvy.semantics.calculator.TypeResult
import com.github.albertocavalcante.gvy.semantics.workspace.MemberLookup
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import java.lang.ref.WeakReference
import java.util.Collections.synchronizedMap
import java.util.WeakHashMap
import java.util.concurrent.CancellationException

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
 * val type = semantics.resolveType(expression, moduleNode)
 * ```
 */
class GroovySemantics(
    private val typeSolver: TypeSolver,
    private val calculatorRegistry: TypeCalculatorRegistry = NativeCalculators.createRegistry(),
    private val workspaceMemberLookup: MemberLookup? = null,
) {
    private companion object {
        const val MAX_CONTEXT_BUILD_ATTEMPTS = 3
    }

    // Weak values break the context -> scope -> module back-reference. A caller owns a context for
    // the duration of its operation; entries can be rebuilt when nobody holds the previous context.
    private val contextCache = synchronizedMap(WeakHashMap<ModuleNode, WeakReference<NativeTypeContext>>())

    // The workspace version each cached context was built at; guarded by the contextCache lock. Locals typed from
    // other files are baked into a context, so it is rebuilt once the workspace has changed (never served stale).
    private val contextVersions = WeakHashMap<ModuleNode, Long>()

    // Thread-local current module for single-parameter API compatibility (avoids sharing this state across threads)
    private val currentModule = ThreadLocal<ModuleNode?>()

    /**
     * Inject semantics into a parsed module.
     * After injection, semantic operations are available.
     */
    fun inject(module: ModuleNode) {
        acquireContext(module)
    }

    private fun acquireContext(module: ModuleNode): NativeTypeContext {
        repeat(MAX_CONTEXT_BUILD_ATTEMPTS) {
            if (Thread.currentThread().isInterrupted) throw CancellationException("Semantic context build interrupted")
            val workspaceVersion = workspaceMemberLookup?.version ?: 0L
            synchronized(contextCache) {
                val cached = contextCache[module]?.get()
                if (cached != null && contextVersions[module] == workspaceVersion) return cached
            }
            // Build outside the lock; unrelated modules must not wait for local type inference.
            val scope = buildRootScope(module)
            val context = NativeTypeContext(
                typeSolver = typeSolver,
                calculatorRegistry = calculatorRegistry,
                scope = scope,
                isStaticCompilation = hasCompileStatic(module),
                workspaceMemberLookup = workspaceMemberLookup,
            )
            populateDeclarations(module, scope, context)
            if (Thread.currentThread().isInterrupted) throw CancellationException("Semantic context build interrupted")

            val published = synchronized(contextCache) {
                if ((workspaceMemberLookup?.version ?: 0L) != workspaceVersion) {
                    null
                } else {
                    val cached = contextCache[module]?.get()
                    if (cached != null && contextVersions[module] == workspaceVersion) {
                        cached
                    } else {
                        contextCache[module] = WeakReference(context)
                        contextVersions[module] = workspaceVersion
                        context
                    }
                }
            }
            if (published != null) return published
        }
        error("Workspace changed repeatedly while building semantic context")
    }

    private fun populateDeclarations(module: ModuleNode, scope: NativeScope, context: NativeTypeContext) {
        val visitor = object : CodeVisitorSupport() {
            override fun visitDeclarationExpression(expression: DeclarationExpression) {
                if (!expression.isMultipleAssignmentDeclaration) {
                    scope.defineDeclaration(expression.variableExpression, calculatorRegistry.calculate(expression, context))
                }
                super.visitDeclarationExpression(expression)
            }
        }
        module.statementBlock?.visit(visitor)
        module.classes.forEach { cls -> cls.methods.forEach { it.code?.visit(visitor) } }
    }

    /**
     * Resolve the type of an AST node using the specified module's context.
     * This is the preferred API for multi-document workspaces.
     */
    fun resolveType(node: ASTNode, module: ModuleNode): SemanticType {
        currentModule.set(module)
        try {
            val context = acquireContext(module)
            return calculatorRegistry.calculate(node, context)
        } finally {
            currentModule.remove()
        }
    }

    /**
     * Resolve the type of an AST node.
     * @deprecated Use resolveType(node, module) for multi-document safety
     */
    @Deprecated(
        "Use resolveType(node, module) for multi-document safety",
        ReplaceWith("resolveType(node, module)"),
    )
    fun resolveType(node: ASTNode): SemanticType {
        try {
            // Legacy API cannot identify the node's module, so it keeps its original first-module behavior.
            val module = synchronized(contextCache) { contextCache.keys.firstOrNull() }
                ?: return SemanticType.Unknown("Node not in injected module")
            val context = acquireContext(module)
            return calculatorRegistry.calculate(node, context)
        } finally {
            currentModule.remove()
        }
    }

    /**
     * Resolve the type of an expression.
     * @deprecated Use resolveType(expression, module) for multi-document safety
     */
    @Deprecated(
        "Use resolveType(expression, module) for multi-document safety",
        ReplaceWith("resolveType(expression, module)"),
    )
    @Suppress("DEPRECATION")
    fun resolveType(expression: Expression): SemanticType = resolveType(expression as ASTNode)

    /**
     * Resolve the type of an AST node, returning Either for explicit error handling.
     *
     * @param node The AST node to resolve type for
     * @return Either a TypeInferenceError or the resolved SemanticType
     */
    fun resolveTypeResult(node: ASTNode): TypeResult {
        try {
            val module = currentModule.get()
                ?: return TypeInferenceError.InternalError("No module injected").left()

            val context = contextCache[module]?.get()
                ?: return TypeInferenceError.InternalError("Module not in context cache").left()

            return calculatorRegistry.calculateResult(node, context)
        } finally {
            currentModule.remove()
        }
    }

    /**
     * Resolve the type of an AST node with explicit module, returning Either.
     */
    fun resolveTypeResult(node: ASTNode, module: ModuleNode): TypeResult {
        currentModule.set(module)
        try {
            val context = acquireContext(module)
            return calculatorRegistry.calculateResult(node, context)
        } finally {
            currentModule.remove()
        }
    }

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

    private fun buildRootScope(module: ModuleNode): NativeScope = NativeScope.fromModule(module)

    private fun hasCompileStatic(module: ModuleNode): Boolean {
        // Check for @CompileStatic annotation on classes
        return module.classes.any { cls ->
            cls.annotations.any {
                it.classNode.name.endsWith("CompileStatic")
            }
        }
    }

    /**
     * Get the type context for a module.
     * Ensures the module is injected first.
     *
     * @param module The module to get context for
     * @return The NativeTypeContext, or null if injection failed
     */
    fun getContext(module: ModuleNode): NativeTypeContext? = acquireContext(module)
}
