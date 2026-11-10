package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.ast.ASTNode
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

    /**
     * Set while a trait's helper class is visited. Its nodes are the compiler's rewrite of the trait body and
     * carry no positions, so position says nothing there; its generated `$` names are left out instead.
     */
    var inTraitHelper = false

    /** Whether [node] was written in the source: the compiler's own nodes have no position. */
    fun isSource(node: ASTNode): Boolean = inTraitHelper || node.lineNumber > 0

    private fun put(found: String) {
        // Only the trait transform's exact own names are left out; anything else, its field helpers included,
        // is kept, since a needless name costs nothing and a dropped one hides a use.
        if (!(inTraitHelper && found in TRAIT_GENERATED_NAMES)) names += found
    }

    private fun put(found: Collection<String>) = found.forEach(::put)

    fun toSet(): Set<String> = Collections.unmodifiableSet(HashSet(names))

    /** Names that are recorded as they are written (imports, named arguments). */
    fun addAll(found: Collection<String>) {
        put(found)
    }

    /** A name whose use was not attributed; null means the name is not a constant, so any name may be meant. */
    fun name(name: String?) {
        put(name ?: WILDCARD)
    }

    /**
     * A call: a dynamic name, a call that no occurrence was stored for ([attributed] false), a spread call, or a
     * reflective call. Either way its named arguments are recorded.
     */
    fun call(call: MethodCallExpression, attributed: Boolean) {
        val name = call.methodAsString
        put(DispatchNames.namedArguments(call.arguments))
        name?.let(DispatchNames::traitField)?.forEach { (field, access) -> member(field, access) }
        if (name == null || !attributed || call.isSpreadSafe) callee(name)
        if (name in REFLECTIVE) {
            val first = (call.arguments as? TupleExpression)?.expressions?.firstOrNull() ?: return
            name(constantString(first))
        }
    }

    /** A call that was not attributed: its name, and the property it may be the accessor of (`getName` is `name`). */
    fun callee(name: String?) {
        name?.let(DispatchNames::traitField)?.forEach { (field, access) -> member(field, access) }
        name(name)
        if (name != null) put(DispatchNames.propertyOf(name))
    }

    /**
     * A property use (`obj.name`, a bare `name`) that was not attributed. Besides the name, the accessors it may
     * call are recorded (`getName`/`isName` for a read, `setName` for a write), so renaming a getter finds it.
     */
    fun member(name: String?, access: Access) {
        name(name)
        if (name != null) put(DispatchNames.accessors(name, access))
    }

    /** Operator syntax on [left] and [right]: the methods Groovy dispatches to (`+` is `plus`). */
    fun operator(type: Int, left: Expression, right: Expression) {
        put(DispatchNames.operators(type, left, right))
    }

    /** Operator syntax with one operand (`-a` is `negative`, `a++` is `next`), unless it is a number. */
    fun operator(method: String, operand: Expression) {
        if (isSource(operand) && !DispatchNames.isNumeric(operand)) put(method)
    }

    /** Syntax that always dispatches to [method] (`o as T` is `asType`, `for (x in o)` is `iterator`). */
    fun dispatch(method: String) {
        put(method)
    }

    /** Annotation member names (`@A(v = 1)` names `v`). */
    fun annotations(annotations: Collection<AnnotationNode>) {
        put(AnnotationMembers.of(annotations))
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
        if (access != Access.WRITE) put("getAt")
        if (access != Access.READ) put("putAt")
        if (!cannotBeName(index)) name(constantString(index))
    }

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

        /** Reflective entry points whose first argument is the name they act on. */
        private val REFLECTIVE = setOf("invokeMethod", "getProperty", "setProperty", "respondsTo", "hasProperty")

        /** Names the trait transform generates into a helper class; nothing in the source is spelled this way. */
        private val TRAIT_GENERATED_NAMES = setOf("\$self", "\$static\$self", "\$init\$", "\$static\$init\$")
    }
}
