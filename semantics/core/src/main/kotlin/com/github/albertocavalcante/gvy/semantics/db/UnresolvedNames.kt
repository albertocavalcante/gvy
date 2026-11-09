package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.RangeExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression

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

    fun toSet(): Set<String> = names.toSet()

    /** A name whose use was not attributed; null means the name is not a constant, so any name may be meant. */
    fun name(name: String?) {
        names += name ?: WILDCARD
    }

    /** A call: dynamic name, any reason it stayed unattributed, a spread call, or a reflective call. */
    fun call(call: MethodCallExpression, outcome: CallOutcome?) {
        val name = call.methodAsString
        if (name == null || outcome is CallOutcome.Unresolved || call.isSpreadSafe) name(name)
        if (name in REFLECTIVE) {
            val first = (call.arguments as? TupleExpression)?.expressions?.firstOrNull() ?: return
            name(constantString(first))
        }
    }

    /**
     * `receiver[index]`. On a Map or List the index is a key or a position, but on any other object Groovy's
     * `getAt(String)` reads the property of that name. An index that cannot be a String is not a name.
     */
    fun index(receiver: Expression, index: Expression, typeOf: (Expression) -> ClassNode?) {
        val keyed = receiver is ListExpression || receiver is MapExpression ||
            typeOf(receiver)?.let(::isKeyedByIndex) == true
        if (!keyed && !cannotBeName(index)) name(constantString(index))
    }

    /** Records [WILDCARD] when any of [classes] declares or inherits a hook that answers calls or reads itself. */
    fun dispatchesDynamically(classes: List<ClassNode>) {
        if (classes.any(::hasDispatchHook)) names += WILDCARD
    }

    private fun hasDispatchHook(type: ClassNode): Boolean {
        val seen = HashSet<String>()
        val chain = generateSequence(type.redirect()) { it.superClass?.redirect() }
            .takeWhile { it.name != OBJECT && seen.add(it.name) }.toList()
        val interfaces = chain.flatMap { it.allInterfaces }
        return (chain + interfaces).any { candidate ->
            candidate.name !in STANDARD_DISPATCHERS &&
                (candidate.name == INTERCEPTABLE || candidate.methods.any { it.name in HOOKS })
        }
    }

    private fun isKeyedByIndex(type: ClassNode): Boolean = type.isArray ||
        listOf(ClassHelper.MAP_TYPE, ClassHelper.LIST_TYPE).any {
            type.name == it.name || type.isDerivedFrom(it) || type.implementsInterface(it)
        }

    private fun cannotBeName(index: Expression): Boolean = when (index) {
        is ConstantExpression -> index.value !is String
        is RangeExpression -> true
        is VariableExpression -> {
            val declared: Variable = index.accessedVariable ?: index
            TypeSignatures.knownTypeOrNull(declared.type)?.let(ClassHelper::isNumberType) == true
        }
        else -> false
    }

    private fun constantString(expression: Expression): String? = (expression as? ConstantExpression)?.value as? String

    companion object {
        /** Stands for every name: a site whose name is computed, or a class that dispatches by itself. */
        const val WILDCARD = "*"

        private const val OBJECT = "java.lang.Object"
        private const val INTERCEPTABLE = "groovy.lang.GroovyInterceptable"

        /** Reflective entry points whose first argument is the name they act on. */
        private val REFLECTIVE = setOf("invokeMethod", "getProperty", "setProperty")

        /** Declaring one of these makes a class answer calls or reads that match no declaration. */
        private val HOOKS = setOf("methodMissing", "propertyMissing", "invokeMethod", "getProperty")

        /** Groovy's own implementations, which only consult the metaclass or a script's binding. */
        private val STANDARD_DISPATCHERS = setOf(
            "groovy.lang.GroovyObject",
            "groovy.lang.GroovyObjectSupport",
            "groovy.lang.Script",
        )
    }
}
