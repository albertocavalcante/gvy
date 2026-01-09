package com.github.albertocavalcante.gvy.semantics.delegation

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.ClosureExpression

/**
 * Resolves implicit receivers in Groovy closures.
 *
 * When a method call or property access occurs without an explicit receiver inside a closure,
 * Groovy uses a delegation chain to resolve it. This resolver implements that chain.
 *
 * Resolution order depends on the delegation strategy:
 * - OWNER_FIRST: this -> owner -> delegate
 * - DELEGATE_FIRST: this -> delegate -> owner
 * - OWNER_ONLY: this -> owner
 * - DELEGATE_ONLY: this -> delegate
 * - TO_SELF: this only
 *
 * @see <a href="https://groovy-lang.org/closures.html#_delegation_strategy">Groovy Delegation Strategy</a>
 * @see <a href="https://github.com/JetBrains/intellij-community/tree/master/plugins/groovy/groovy-psi/src/org/jetbrains/plugins/groovy/lang/resolve/delegatesTo">IntelliJ delegatesTo</a>
 */
class DelegationResolver {

    /**
     * Resolves the implicit receiver candidates for a closure.
     *
     * @param closure The closure expression
     * @param strategy The delegation strategy (default: OWNER_FIRST per Groovy spec)
     * @return List of candidate types to check for method/property resolution, in order
     */
    fun resolveReceiverCandidates(
        closure: ClosureExpression,
        strategy: DelegationStrategy = DelegationStrategy.DEFAULT,
    ): List<ReceiverCandidate> {
        val thisType = getClosureType(closure)
        val ownerType = getOwnerType(closure)
        val delegateType = getDelegateType(closure)

        return when (strategy) {
            DelegationStrategy.OWNER_FIRST -> listOfNotNull(
                thisType?.let { ReceiverCandidate(it, ReceiverKind.THIS) },
                ownerType?.let { ReceiverCandidate(it, ReceiverKind.OWNER) },
                delegateType?.let { ReceiverCandidate(it, ReceiverKind.DELEGATE) },
            )
            DelegationStrategy.DELEGATE_FIRST -> listOfNotNull(
                thisType?.let { ReceiverCandidate(it, ReceiverKind.THIS) },
                delegateType?.let { ReceiverCandidate(it, ReceiverKind.DELEGATE) },
                ownerType?.let { ReceiverCandidate(it, ReceiverKind.OWNER) },
            )
            DelegationStrategy.OWNER_ONLY -> listOfNotNull(
                thisType?.let { ReceiverCandidate(it, ReceiverKind.THIS) },
                ownerType?.let { ReceiverCandidate(it, ReceiverKind.OWNER) },
            )
            DelegationStrategy.DELEGATE_ONLY -> listOfNotNull(
                thisType?.let { ReceiverCandidate(it, ReceiverKind.THIS) },
                delegateType?.let { ReceiverCandidate(it, ReceiverKind.DELEGATE) },
            )
            DelegationStrategy.TO_SELF -> listOfNotNull(
                thisType?.let { ReceiverCandidate(it, ReceiverKind.THIS) },
            )
        }
    }

    /**
     * Resolves a method call to the appropriate receiver.
     *
     * @param closure The closure expression
     * @param methodName The method name to resolve
     * @param strategy The delegation strategy
     * @return The resolved method and its receiver, or null if not found
     */
    fun resolveMethod(
        closure: ClosureExpression,
        methodName: String,
        strategy: DelegationStrategy = DelegationStrategy.DEFAULT,
    ): ResolvedMethod? {
        val candidates = resolveReceiverCandidates(closure, strategy)

        for (candidate in candidates) {
            val method = findMethod(candidate.type, methodName)
            if (method != null) {
                return ResolvedMethod(method, candidate)
            }
        }

        return null
    }

    /**
     * Gets the type of the closure itself (this).
     */
    private fun getClosureType(closure: ClosureExpression): ClassNode? {
        // Closure's 'this' is the closure itself - usually Closure<V>
        return closure.type
    }

    /**
     * Gets the owner type - the enclosing class or closure.
     */
    private fun getOwnerType(closure: ClosureExpression): ClassNode? {
        // Walk up to find enclosing class
        var node = closure.declaringClass
        if (node != null) return node

        // TODO(#638): Handle nested closures - owner should be outer closure's owner
        return null
    }

    /**
     * Gets the delegate type from @DelegatesTo annotation or runtime assignment.
     */
    private fun getDelegateType(closure: ClosureExpression): ClassNode? {
        // Check for @DelegatesTo annotation on the parameter
        val delegatesToType = extractDelegatesToAnnotation(closure)
        if (delegatesToType != null) return delegatesToType

        // TODO(#638): Track runtime delegate assignments
        // This would require data flow analysis to track:
        // closure.delegate = someObject

        return null
    }

    /**
     * Extracts @DelegatesTo annotation from closure parameter.
     */
    private fun extractDelegatesToAnnotation(closure: ClosureExpression): ClassNode? {
        // TODO(#638): Implement @DelegatesTo extraction
        // 1. Find the method parameter this closure is passed to
        // 2. Check for @DelegatesTo(SomeClass.class) annotation
        // 3. Return SomeClass node

        // For now, return null - will be implemented with full AST context
        return null
    }

    /**
     * Finds a method by name on a class.
     */
    private fun findMethod(classNode: ClassNode, methodName: String): MethodNode? {
        // Direct methods
        val direct = classNode.getMethods(methodName).firstOrNull()
        if (direct != null) return direct

        // Check superclass
        val superClass = classNode.superClass
        if (superClass != null) {
            val inherited = findMethod(superClass, methodName)
            if (inherited != null) return inherited
        }

        // Check interfaces
        for (iface in classNode.interfaces) {
            val fromInterface = findMethod(iface, methodName)
            if (fromInterface != null) return fromInterface
        }

        return null
    }
}

/**
 * A candidate receiver for method/property resolution.
 */
data class ReceiverCandidate(val type: ClassNode, val kind: ReceiverKind)

/**
 * The kind of receiver in the delegation chain.
 */
enum class ReceiverKind {
    THIS,
    OWNER,
    DELEGATE,
}

/**
 * A method resolved to a specific receiver.
 */
data class ResolvedMethod(val method: MethodNode, val receiver: ReceiverCandidate)
