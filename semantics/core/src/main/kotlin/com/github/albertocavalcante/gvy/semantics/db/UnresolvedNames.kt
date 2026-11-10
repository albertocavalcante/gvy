package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.RangeExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import java.util.Collections

/**
 * Collects the names a document uses that the index could not attribute to a declaration, for
 * [SemanticDocument.unresolvedNames]. The index is deliberately silent about uncertain uses, so a
 * consumer that must not miss one (a workspace-wide rename) needs to know where they are.
 *
 * A site whose name is not statically known (a GString member name, `invokeMethod(n, ...)`, a class that
 * dispatches by itself) could be any name and is recorded as [WILDCARD].
 */
internal class UnresolvedNames {
    private val names = HashSet<String>()

    fun toSet(): Set<String> = Collections.unmodifiableSet(HashSet(names))

    /** Names that are recorded as they are written (imports, named arguments). */
    fun addAll(found: Collection<String>) {
        names += found
    }

    /** A name whose use was not attributed; null means the name is not a constant, so any name may be meant. */
    fun name(name: String?) {
        names += name ?: WILDCARD
    }

    /**
     * A call: a dynamic name, a call that no occurrence was stored for ([attributed] false), a spread call, or a
     * reflective call. Either way its named arguments are recorded.
     */
    fun call(call: MethodCallExpression, attributed: Boolean) {
        val name = call.methodAsString
        names += DispatchNames.namedArguments(call.arguments)
        if (name == null || !attributed || call.isSpreadSafe) name(name)
        if (name in REFLECTIVE) {
            val first = (call.arguments as? TupleExpression)?.expressions?.firstOrNull() ?: return
            name(constantString(first))
        }
    }

    /**
     * A property use (`obj.name`, a bare `name`) that was not attributed. Besides the name, the accessors it may
     * call are recorded (`getName`/`isName` for a read, `setName` for a write), so renaming a getter finds it.
     */
    fun member(name: String?, access: Access) {
        name(name)
        if (name != null) names += DispatchNames.accessors(name, access)
    }

    /** Operator syntax on [left] and [right]: the methods Groovy dispatches to (`+` is `plus`). */
    fun operator(type: Int, left: Expression, right: Expression) {
        names += DispatchNames.operators(type, left, right)
    }

    /** Operator syntax with one operand (`-a` is `negative`, `a++` is `next`), unless it is a number. */
    fun operator(method: String, operand: Expression) {
        if (DispatchNames.isSource(operand) && !DispatchNames.isNumeric(operand)) names += method
    }

    /** Syntax that always dispatches to [method] (`o as T` is `asType`, `for (x in o)` is `iterator`). */
    fun dispatch(method: String) {
        names += method
    }

    /** Annotation member names (`@A(v = 1)` names `v`). */
    fun annotations(annotations: Collection<AnnotationNode>) {
        names += DispatchNames.annotationMembers(annotations)
    }

    /**
     * `receiver[index]`. On a Map, List or array the index is a key or a position, but on any other object Groovy
     * calls `getAt`/`putAt`, and `getAt(String)` reads the property of that name. An index that cannot be a
     * String is not a name.
     */
    fun index(receiver: Expression, index: Expression, typeOf: (Expression) -> ClassNode?, access: Access) {
        val keyed = receiver is ListExpression || receiver is MapExpression ||
            typeOf(receiver)?.let(::isKeyedByIndex) == true
        if (keyed) return
        if (access != Access.WRITE) names += "getAt"
        if (access != Access.READ) names += "putAt"
        if (!cannotBeName(index)) name(constantString(index))
    }

    /** Records [WILDCARD] when any of [classes] or a supertype of one answers calls or reads by itself. */
    fun dispatchesDynamically(classes: List<ClassNode>) {
        if (classes.any(::hasDispatchHook)) names += WILDCARD
    }

    private fun hasDispatchHook(type: ClassNode): Boolean {
        val chain = superclassChain(type) ?: return false
        return (chain + chain.flatMap { it.allInterfaces }).any {
            it.name !in STANDARD_DISPATCHERS && !isGenerated(it) && GroovyDispatch.dispatchesByItself(it)
        }
    }

    /** [type] and its superclasses below Object, or null for a generated class or a cyclic hierarchy. */
    private fun superclassChain(type: ClassNode): List<ClassNode>? {
        if (isGenerated(type)) return null
        val chain = ArrayList<ClassNode>()
        val seen = HashSet<String>()
        var node: ClassNode? = type.redirect()
        while (node != null && node.name != OBJECT) {
            // A class that extends itself is a compile error, and the dispatch helpers would follow it forever.
            if (!seen.add(node.name)) return null
            chain += node
            node = node.superClass?.redirect()
        }
        return chain
    }

    private fun isGenerated(type: ClassNode) = type.isSynthetic || TRAIT_HELPER in type.name

    private fun isKeyedByIndex(type: ClassNode): Boolean = type.isArray ||
        listOf(ClassHelper.MAP_TYPE, ClassHelper.LIST_TYPE).any {
            type.name == it.name || type.isDerivedFrom(it) || type.implementsInterface(it)
        }

    private fun cannotBeName(index: Expression): Boolean = when (index) {
        is ConstantExpression -> index.value !is String
        is RangeExpression -> true
        is VariableExpression -> DispatchNames.isNumeric(index)
        else -> false
    }

    private fun constantString(expression: Expression): String? = (expression as? ConstantExpression)?.value as? String

    companion object {
        /** Stands for every name: a site whose name is computed, or a class that dispatches by itself. */
        const val WILDCARD = "*"

        private const val OBJECT = "java.lang.Object"
        private const val TRAIT_HELPER = "\$Trait\$"

        /** Reflective entry points whose first argument is the name they act on. */
        private val REFLECTIVE = setOf("invokeMethod", "getProperty", "setProperty", "respondsTo", "hasProperty")

        /** Groovy's own implementations, which only consult the metaclass or a script's binding. */
        private val STANDARD_DISPATCHERS = setOf(
            "groovy.lang.GroovyObject",
            "groovy.lang.GroovyObjectSupport",
            "groovy.lang.Script",
        )
    }
}
