package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.syntax.Types

/** How a property is used: read, written, or both (`x += 1`, `x++`). */
internal enum class Access { READ, WRITE, BOTH }

/** The method names Groovy dispatches to for syntax that never names them: operators, accessors, annotations. */
internal object DispatchNames {
    private val compare = listOf("compareTo")
    private val equality = listOf("equals", "compareTo")

    private val binary: Map<Int, List<String>> = mapOf(
        Types.PLUS to listOf("plus"), Types.PLUS_EQUAL to listOf("plus"),
        Types.MINUS to listOf("minus"), Types.MINUS_EQUAL to listOf("minus"),
        Types.MULTIPLY to listOf("multiply"), Types.MULTIPLY_EQUAL to listOf("multiply"),
        Types.DIVIDE to listOf("div"), Types.DIVIDE_EQUAL to listOf("div"),
        Types.INTDIV to listOf("intdiv"), Types.INTDIV_EQUAL to listOf("intdiv"),
        Types.MOD to listOf("mod"), Types.MOD_EQUAL to listOf("mod"),
        Types.POWER to listOf("power"), Types.POWER_EQUAL to listOf("power"),
        Types.LEFT_SHIFT to listOf("leftShift"), Types.LEFT_SHIFT_EQUAL to listOf("leftShift"),
        Types.RIGHT_SHIFT to listOf("rightShift"), Types.RIGHT_SHIFT_EQUAL to listOf("rightShift"),
        Types.RIGHT_SHIFT_UNSIGNED to listOf("rightShiftUnsigned"),
        Types.RIGHT_SHIFT_UNSIGNED_EQUAL to listOf("rightShiftUnsigned"),
        Types.BITWISE_OR to listOf("or"), Types.BITWISE_OR_EQUAL to listOf("or"),
        Types.BITWISE_AND to listOf("and"), Types.BITWISE_AND_EQUAL to listOf("and"),
        Types.BITWISE_XOR to listOf("xor"), Types.BITWISE_XOR_EQUAL to listOf("xor"),
        Types.COMPARE_TO to compare, Types.COMPARE_LESS_THAN to compare,
        Types.COMPARE_LESS_THAN_EQUAL to compare, Types.COMPARE_GREATER_THAN to compare,
        Types.COMPARE_GREATER_THAN_EQUAL to compare,
        Types.COMPARE_EQUAL to equality, Types.COMPARE_NOT_EQUAL to equality,
        Types.KEYWORD_IN to listOf("isCase"),
    )

    /** The methods `left <operator> right` may call; none when both are numbers, or for `x == null`. */
    fun operators(type: Int, left: Expression, right: Expression): List<String> {
        val nullCheck = type in setOf(Types.COMPARE_EQUAL, Types.COMPARE_NOT_EQUAL) && (isNull(left) || isNull(right))
        return if (nullCheck || (isNumeric(left) && isNumeric(right))) emptyList() else binary[type].orEmpty()
    }

    private const val OBJECT = "java.lang.Object"
    private const val TRAIT_HELPER = "\$Trait\$"

    /** Groovy's own implementations, which only consult the metaclass or a script's binding. */
    private val standardDispatchers = setOf(
        "groovy.lang.GroovyObject",
        "groovy.lang.GroovyObjectSupport",
        "groovy.lang.Script",
    )

    /** The class holding a trait's method bodies; the compiler makes it, and its nodes carry no positions. */
    fun isTraitHelper(type: ClassNode): Boolean = TRAIT_HELPER in type.name && !type.isInterface

    /** Whether any of [classes] or a supertype of one answers calls or reads by itself (see [GroovyDispatch]). */
    fun dispatchesDynamically(classes: List<ClassNode>): Boolean = classes.any(::hasDispatchHook)

    private fun hasDispatchHook(type: ClassNode): Boolean {
        val chain = superclassChain(type) ?: return false
        return (chain + chain.flatMap { it.allInterfaces }).any {
            it.name !in standardDispatchers && !isGenerated(it) && GroovyDispatch.dispatchesByItself(it)
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

    private val accessorPrefix = Regex("^(get|set|is)(\\p{Lu}.*)$")

    /**
     * The trait fields a helper the trait transform calls may stand for, with how they are used; empty for any
     * other name. Groovy names the helper `<trait FQN with . as _>__<field>$get` (or `$set`, static fields too),
     * so a trait or package name containing `__` or ending in `_` makes the split ambiguous: every split after a
     * `__` is a candidate (`T___cnt$get` gives `_cnt` and `cnt`). Extra candidates are harmless here.
     */
    fun traitField(method: String): List<Pair<String, Access>> {
        val access = when {
            method.endsWith(TRAIT_GETTER) -> Access.READ
            method.endsWith(TRAIT_SETTER) -> Access.WRITE
            else -> return emptyList()
        }
        val body = method.dropLast(TRAIT_GETTER.length)
        return generateSequence(body.indexOf("__")) { body.indexOf("__", it + 1).takeIf { next -> next >= 0 } }
            .takeWhile { it >= 0 }
            .map { body.substring(it + 2) }
            .filter { it.isNotEmpty() }
            .map { it to access }
            .toList()
    }

    private const val TRAIT_GETTER = "\$get"
    private const val TRAIT_SETTER = "\$set"

    /**
     * The property an accessor-shaped call may stand for: `getTarget`/`isTarget`/`setTarget` give `target`
     * (the JavaBeans spelling, which keeps `URL`, and Groovy's `uRL` are both returned).
     */
    fun propertyOf(method: String): Set<String> {
        val rest = accessorPrefix.matchEntire(method)?.groupValues?.get(2) ?: return emptySet()
        // `URL` stays `URL` for the JavaBeans rule; Groovy's own spelling lowercases the first letter alone.
        val bean = if (rest.length > 1 && rest[1].isUpperCase()) rest else rest.replaceFirstChar(Char::lowercaseChar)
        return setOf(bean, rest.replaceFirstChar(Char::lowercaseChar))
    }

    /** Whether [expression] is statically a number, whose operators are the language's own. */
    fun isNumeric(expression: Expression): Boolean = when (expression) {
        is ConstantExpression -> expression.value is Number
        is VariableExpression -> {
            val declared: Variable = expression.accessedVariable ?: expression
            TypeSignatures.knownTypeOrNull(declared.type)?.let(ClassHelper::isNumberType) == true
        }
        else -> false
    }

    private fun isNull(expression: Expression) = expression is ConstantExpression && expression.isNullExpression

    /** The accessors a property named [name] may be read or written through: `getName`, `isName`, `setName`. */
    fun accessors(name: String, access: Access): List<String> {
        val capitalized = name.replaceFirstChar(Char::uppercaseChar)
        val read = if (access != Access.WRITE) listOf("get$capitalized", "is$capitalized") else emptyList()
        return read + if (access != Access.READ) listOf("set$capitalized") else emptyList()
    }

    /**
     * The names a call's named arguments set: `new P(target: 'x')` writes the property `target`, through
     * `setTarget` when there is one.
     * A computed key (or `*:map`) of a named-argument list may be any name.
     */
    fun namedArguments(arguments: Expression): Set<String> {
        val maps = (arguments as? TupleExpression)?.expressions.orEmpty().filterIsInstance<MapExpression>()
        return maps.flatMapTo(HashSet()) { map ->
            map.mapEntryExpressions.mapNotNull { entry ->
                val key = entry.keyExpression
                when {
                    key is ConstantExpression -> (key.value as? String)?.let {
                        listOf(it) + accessors(it, Access.WRITE)
                    }
                    map is NamedArgumentListExpression -> listOf(UnresolvedNames.WILDCARD)
                    else -> null
                }
            }.flatten()
        }
    }
}
