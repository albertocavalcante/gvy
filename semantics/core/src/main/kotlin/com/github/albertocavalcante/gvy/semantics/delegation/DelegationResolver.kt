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
        val thisCandidate = getClosureType(closure)?.let { ReceiverCandidate(it, ReceiverKind.THIS) }
        val ownerCandidate = getOwnerType(closure)?.let { ReceiverCandidate(it, ReceiverKind.OWNER) }
        val delegateCandidate = getDelegateType(closure)?.let { ReceiverCandidate(it, ReceiverKind.DELEGATE) }

        val remainingCandidates = when (strategy) {
            DelegationStrategy.OWNER_FIRST -> listOfNotNull(ownerCandidate, delegateCandidate)
            DelegationStrategy.DELEGATE_FIRST -> listOfNotNull(delegateCandidate, ownerCandidate)
            DelegationStrategy.OWNER_ONLY -> listOfNotNull(ownerCandidate)
            DelegationStrategy.DELEGATE_ONLY -> listOfNotNull(delegateCandidate)
            DelegationStrategy.TO_SELF -> emptyList()
        }

        return listOfNotNull(thisCandidate) + remainingCandidates
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
    ): ResolvedMethod? = resolveReceiverCandidates(closure, strategy)
        .asSequence()
        .mapNotNull { candidate ->
            findMethod(candidate.type, methodName)?.let { ResolvedMethod(it, candidate) }
        }
        .firstOrNull()

    /**
     * Gets the type of 'this' in the closure - the enclosing class.
     */
    private fun getClosureType(closure: ClosureExpression): ClassNode? {
        // In Groovy, 'this' in a closure refers to the enclosing class, not the closure object
        return closure.declaringClass
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
     *
     * ClassNode.getMethods(name) searches the entire class hierarchy (superclasses and interfaces).
     */
    private fun findMethod(classNode: ClassNode, methodName: String): MethodNode? =
        classNode.getMethods(methodName).firstOrNull()

    /**
     * Finds a method by name on a class, optionally matching parameter types.
     *
     * This method supports method overload resolution by matching parameter types.
     * ClassNode.getMethods(name) searches the entire class hierarchy (superclasses and interfaces).
     *
     * @param classNode The class to search
     * @param methodName The method name to find
     * @param parameterTypes Optional list of parameter type names for overload resolution.
     *                       If null, returns the first method matching the name (backward compatibility).
     *                       If provided, finds the method with matching parameter count and types.
     * @return The matching method, or null if not found
     */
    fun findMethodWithParams(classNode: ClassNode, methodName: String, parameterTypes: List<String>?): MethodNode? {
        val candidateMethods = classNode.getMethods(methodName)

        // If no parameter types specified, return first match (backward compatibility)
        if (parameterTypes == null) {
            return candidateMethods.firstOrNull()
        }

        // Find method matching parameter count and types
        return candidateMethods.firstOrNull { method ->
            val methodParams = method.parameters
            methodParams.size == parameterTypes.size && methodParams.indices.all { i ->
                val paramType = methodParams[i].type
                val expectedType = parameterTypes[i]
                // Match by type name (handles both fully qualified and simple names)
                paramType.name == expectedType || paramType.nameWithoutPackage == expectedType
            }
        }
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
