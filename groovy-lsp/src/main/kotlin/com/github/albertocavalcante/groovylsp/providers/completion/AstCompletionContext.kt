package com.github.albertocavalcante.groovylsp.providers.completion

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression

/**
 * AST-based completion context information.
 *
 * Unlike text/regex-based detection, this uses AST traversal to determine
 * the completion context accurately, even in complex nested structures.
 *
 * Inspired by IntelliJ's PSI-based context detection.
 *
 * @see <a href="https://github.com/JetBrains/intellij-community/tree/master/plugins/groovy/groovy-psi/src/org/jetbrains/plugins/groovy/lang/completion">IntelliJ Groovy Completion</a>
 */
sealed interface AstCompletionContext {

    /**
     * Completion at top level of file (outside any class/method).
     */
    data object TopLevel : AstCompletionContext

    /**
     * Completion inside a class body.
     */
    data class ClassBody(val classNode: ClassNode) : AstCompletionContext

    /**
     * Completion inside a method body.
     */
    data class MethodBody(val methodNode: MethodNode, val enclosingClass: ClassNode?) : AstCompletionContext

    /**
     * Completion inside a closure.
     *
     * This is critical for proper delegate resolution in DSLs.
     */
    data class ClosureBody(
        val closure: ClosureExpression,
        val ownerType: ClassNode?,
        val delegateType: ClassNode?,
        val enclosingMethodCall: MethodCallExpression?,
        val nestingDepth: Int,
    ) : AstCompletionContext {
        /**
         * Whether this closure is a DSL block (method call with closure argument).
         */
        val isDslBlock: Boolean get() = enclosingMethodCall != null
    }

    /**
     * Completion after a dot (member access).
     */
    data class MemberAccess(val receiverType: ClassNode?, val receiverName: String?, val isStatic: Boolean) :
        AstCompletionContext

    /**
     * Completion in a method call's argument list.
     */
    data class MethodArgument(
        val methodCall: MethodCallExpression,
        val argumentIndex: Int,
        val expectedType: ClassNode?,
    ) : AstCompletionContext

    /**
     * Completion in a builder pattern context.
     *
     * Builder patterns are characterized by chained method calls that return
     * the builder itself.
     */
    data class BuilderContext(val builderType: ClassNode, val methodChain: List<String>) : AstCompletionContext

    /**
     * Completion in an annotation.
     */
    data class AnnotationContext(val annotationType: ClassNode?, val attributeName: String?) : AstCompletionContext

    /**
     * Completion in an import statement.
     */
    data class ImportContext(val prefix: String, val isStatic: Boolean) : AstCompletionContext

    /**
     * Unknown or unrecognized context - fallback to general completion.
     */
    data object Unknown : AstCompletionContext
}

/**
 * Information about the enclosing closures for delegation resolution.
 */
data class ClosureChain(val closures: List<ClosureInfo>) {
    val depth: Int get() = closures.size
    val innermost: ClosureInfo? get() = closures.lastOrNull()
    val outermost: ClosureInfo? get() = closures.firstOrNull()
}

/**
 * Information about a single closure in the chain.
 */
data class ClosureInfo(
    val closure: ClosureExpression,
    val ownerType: ClassNode?,
    val delegateType: ClassNode?,
    val delegationStrategy: Int,
    val enclosingMethodCall: MethodCallExpression?,
)
