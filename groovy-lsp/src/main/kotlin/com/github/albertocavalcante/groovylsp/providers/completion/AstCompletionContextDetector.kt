package com.github.albertocavalcante.groovylsp.providers.completion

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement

/**
 * AST-based completion context detector.
 *
 * This replaces regex/text-based detection with proper AST traversal,
 * providing accurate context detection even in complex nested structures
 * like closures, builders, and DSL blocks.
 *
 * Key improvements over text-based detection:
 * - Proper closure nesting detection
 * - Accurate delegate type inference for DSL blocks
 * - Builder pattern recognition
 * - Handles incomplete/broken syntax gracefully
 *
 * @see <a href="https://github.com/scalameta/metals/blob/main/metals/src/main/scala/scala/meta/internal/pc/CompletionProvider.scala">Metals CompletionProvider</a>
 * @see <a href="https://github.com/JetBrains/intellij-community/tree/master/plugins/groovy/groovy-psi/src/org/jetbrains/plugins/groovy/lang/completion">IntelliJ Groovy Completion</a>
 */
object AstCompletionContextDetector {

    /**
     * Detects the completion context from the AST at the given position.
     *
     * @param nodeAtCursor The AST node at or near the cursor position
     * @param astModel The AST model for parent traversal
     * @param moduleNode The module node for the file
     * @return The detected completion context
     */
    fun detect(nodeAtCursor: ASTNode?, astModel: GroovyAstModel, moduleNode: ModuleNode?): AstCompletionContext {
        if (nodeAtCursor == null) {
            return AstCompletionContext.TopLevel
        }

        // Check for member access context first (most specific)
        detectMemberAccess(nodeAtCursor, astModel)?.let { return it }

        // Check for closure context (critical for DSLs)
        detectClosureContext(nodeAtCursor, astModel)?.let { return it }

        // Check for method argument context
        detectMethodArgumentContext(nodeAtCursor, astModel)?.let { return it }

        // Check for annotation context
        detectAnnotationContext(nodeAtCursor, astModel)?.let { return it }

        // Check for method/class body context
        detectEnclosingScope(nodeAtCursor, astModel, moduleNode)?.let { return it }

        return AstCompletionContext.Unknown
    }

    /**
     * Detects if we're in a member access context (after a dot).
     */
    private fun detectMemberAccess(node: ASTNode, astModel: GroovyAstModel): AstCompletionContext.MemberAccess? {
        val parent = astModel.getParent(node)

        when (parent) {
            is PropertyExpression -> {
                if (!parent.isImplicitThis) {
                    val receiverType = parent.objectExpression.type
                    val receiverName = extractReceiverName(parent.objectExpression)
                    return AstCompletionContext.MemberAccess(
                        receiverType = receiverType,
                        receiverName = receiverName,
                        isStatic = false,
                    )
                }
            }
            is MethodCallExpression -> {
                if (!parent.isImplicitThis) {
                    val objectExpr = parent.objectExpression
                    val receiverType = objectExpr.type
                    val receiverName = extractReceiverName(objectExpr)
                    return AstCompletionContext.MemberAccess(
                        receiverType = receiverType,
                        receiverName = receiverName,
                        isStatic = false,
                    )
                }
            }
        }

        return null
    }

    /**
     * Detects if we're inside a closure, and provides delegation info.
     */
    private fun detectClosureContext(node: ASTNode, astModel: GroovyAstModel): AstCompletionContext.ClosureBody? {
        val closureChain = buildClosureChain(node, astModel)
        val innermost = closureChain.innermost ?: return null

        return AstCompletionContext.ClosureBody(
            closure = innermost.closure,
            ownerType = innermost.ownerType,
            delegateType = innermost.delegateType,
            enclosingMethodCall = innermost.enclosingMethodCall,
            nestingDepth = closureChain.depth,
        )
    }

    /**
     * Builds the chain of enclosing closures for delegation resolution.
     */
    private fun buildClosureChain(node: ASTNode, astModel: GroovyAstModel): ClosureChain {
        val closures = mutableListOf<ClosureInfo>()
        var current: ASTNode? = node

        while (current != null) {
            if (current is ClosureExpression) {
                val info = buildClosureInfo(current, astModel)
                closures.add(0, info) // Add to front to maintain outer-to-inner order
            }
            current = astModel.getParent(current)
        }

        return ClosureChain(closures)
    }

    /**
     * Builds closure info including delegation details.
     */
    private fun buildClosureInfo(closure: ClosureExpression, astModel: GroovyAstModel): ClosureInfo {
        val parent = astModel.getParent(closure)
        val enclosingMethodCall = findEnclosingMethodCall(parent, astModel)

        // Infer delegate type from @DelegatesTo annotation or method signature
        val delegateType = inferDelegateType(closure, enclosingMethodCall, astModel)

        // Owner is the enclosing class or outer closure's owner
        val ownerType = findOwnerType(closure, astModel)

        return ClosureInfo(
            closure = closure,
            ownerType = ownerType,
            delegateType = delegateType,
            delegationStrategy = groovy.lang.Closure.OWNER_FIRST, // Default
            enclosingMethodCall = enclosingMethodCall,
        )
    }

    /**
     * Infers the delegate type from @DelegatesTo annotation or known DSL patterns.
     *
     * NOTE: Delegate type inference is currently not implemented. This method is intentionally
     * stubbed and always returns null until the TODO is fully addressed (including DSL-based
     * heuristics and @DelegatesTo extraction). The DSL pattern matching below is non-functional
     * because [findClassByName] always returns null.
     */
    private fun inferDelegateType(
        closure: ClosureExpression,
        enclosingMethodCall: MethodCallExpression?,
        astModel: GroovyAstModel,
    ): ClassNode? {
        // TODO: Implement extraction of delegate type from @DelegatesTo annotations
        // TODO: Implement actual class lookup in findClassByName to enable DSL pattern matching

        val methodName = enclosingMethodCall?.methodAsString ?: return null

        // Known DSL patterns (Gradle, Jenkins, etc.) - currently non-functional
        return when {
            // Gradle patterns
            methodName == "dependencies" -> findClassByName("org.gradle.api.artifacts.dsl.DependencyHandler")
            methodName == "plugins" -> findClassByName("org.gradle.plugin.use.PluginDependenciesSpec")
            methodName == "repositories" -> findClassByName("org.gradle.api.artifacts.dsl.RepositoryHandler")
            methodName == "buildscript" -> findClassByName("org.gradle.api.initialization.dsl.ScriptHandler")
            methodName == "allprojects" || methodName == "subprojects" ->
                findClassByName("org.gradle.api.Project")

            // Jenkins patterns
            methodName == "pipeline" -> findClassByName("org.jenkinsci.plugins.pipeline.PipelineBuilder")
            methodName == "node" -> findClassByName("org.jenkinsci.plugins.workflow.cps.CpsScript")
            methodName == "stage" -> findClassByName("org.jenkinsci.plugins.workflow.cps.CpsScript")
            methodName == "steps" -> findClassByName("org.jenkinsci.plugins.workflow.cps.CpsScript")

            // Spock patterns
            methodName in setOf("expect", "when", "then", "given", "cleanup", "where") ->
                findClassByName("spock.lang.Specification")

            else -> null
        }
    }

    /**
     * Finds the owner type (enclosing class or outer closure's owner).
     */
    private fun findOwnerType(closure: ClosureExpression, astModel: GroovyAstModel): ClassNode? {
        var current: ASTNode? = astModel.getParent(closure)

        while (current != null) {
            when (current) {
                is ClassNode -> return current
                is MethodNode -> return current.declaringClass
            }
            current = astModel.getParent(current)
        }

        return closure.type
    }

    /**
     * Detects if we're in a method argument position.
     */
    private fun detectMethodArgumentContext(
        node: ASTNode,
        astModel: GroovyAstModel,
    ): AstCompletionContext.MethodArgument? {
        var current: ASTNode? = node
        var argumentIndex = -1

        while (current != null) {
            val parent = astModel.getParent(current)

            if (parent is ArgumentListExpression) {
                val expressions = parent.expressions
                argumentIndex = expressions.indexOf(current)
                if (argumentIndex < 0) {
                    argumentIndex = expressions.size // Cursor is at end
                }

                val grandparent = astModel.getParent(parent)
                if (grandparent is MethodCallExpression) {
                    return AstCompletionContext.MethodArgument(
                        methodCall = grandparent,
                        argumentIndex = argumentIndex,
                        expectedType = inferExpectedArgumentType(grandparent, argumentIndex),
                    )
                }
            }

            current = parent
        }

        return null
    }

    /**
     * Infers the expected type for an argument at the given index.
     */
    private fun inferExpectedArgumentType(methodCall: MethodCallExpression, argumentIndex: Int): ClassNode? {
        // TODO(#657): Implement method parameter type lookup
        return null
    }

    /**
     * Detects if we're in an annotation context.
     */
    private fun detectAnnotationContext(
        node: ASTNode,
        astModel: GroovyAstModel,
    ): AstCompletionContext.AnnotationContext? {
        var current: ASTNode? = node

        while (current != null) {
            if (current is AnnotationNode) {
                return AstCompletionContext.AnnotationContext(
                    annotationType = current.classNode,
                    attributeName = null, // TODO: Detect which attribute we're in
                )
            }
            current = astModel.getParent(current)
        }

        return null
    }

    /**
     * Detects the enclosing scope (method body, class body, or top level).
     */
    private fun detectEnclosingScope(
        node: ASTNode,
        astModel: GroovyAstModel,
        moduleNode: ModuleNode?,
    ): AstCompletionContext? {
        var current: ASTNode? = node

        while (current != null) {
            when (current) {
                is MethodNode -> {
                    return AstCompletionContext.MethodBody(
                        methodNode = current,
                        enclosingClass = current.declaringClass,
                    )
                }
                is ClassNode -> {
                    return AstCompletionContext.ClassBody(classNode = current)
                }
            }
            current = astModel.getParent(current)
        }

        return AstCompletionContext.TopLevel
    }

    /**
     * Finds the enclosing method call expression.
     */
    private fun findEnclosingMethodCall(node: ASTNode?, astModel: GroovyAstModel): MethodCallExpression? {
        var current: ASTNode? = node

        while (current != null) {
            if (current is MethodCallExpression) {
                return current
            }
            // Check if this node is an argument to a method call
            val parent = astModel.getParent(current)
            if (parent is ArgumentListExpression) {
                val grandparent = astModel.getParent(parent)
                if (grandparent is MethodCallExpression) {
                    return grandparent
                }
            }
            // Check if this is an expression statement in a method call's closure
            if (parent is BlockStatement) {
                val grandparent = astModel.getParent(parent)
                if (grandparent is ClosureExpression) {
                    val greatGrandparent = astModel.getParent(grandparent)
                    if (greatGrandparent is ArgumentListExpression) {
                        val methodCall = astModel.getParent(greatGrandparent)
                        if (methodCall is MethodCallExpression) {
                            return methodCall
                        }
                    }
                }
            }
            current = parent
        }

        return null
    }

    /**
     * Extracts the receiver name from an expression.
     */
    private fun extractReceiverName(expr: org.codehaus.groovy.ast.expr.Expression): String? = when (expr) {
        is VariableExpression -> expr.name
        is PropertyExpression -> expr.propertyAsString
        else -> null
    }

    /**
     * Placeholder for class lookup by name.
     */
    private fun findClassByName(fqn: String): ClassNode? {
        // TODO: Implement actual class lookup from classpath
        return null
    }

    /**
     * Checks if a node is inside a builder pattern (chained method calls).
     */
    private fun isBuilderPattern(node: ASTNode, astModel: GroovyAstModel): Boolean {
        var methodCallCount = 0
        var current: ASTNode? = node

        while (current != null) {
            if (current is MethodCallExpression) {
                methodCallCount++
                if (methodCallCount >= 2) {
                    return true // At least 2 chained calls
                }
                // Check if this method call is the receiver of another method call
                val parent = astModel.getParent(current)
                if (parent is MethodCallExpression && parent.objectExpression == current) {
                    // Part of a chained call: move up to the parent call and continue counting
                    current = parent
                    continue
                } else {
                    // Chain ended; no need to traverse further for builder detection
                    break
                }
            }
            current = astModel.getParent(current)
        }

        return methodCallCount >= 2
    }
}
