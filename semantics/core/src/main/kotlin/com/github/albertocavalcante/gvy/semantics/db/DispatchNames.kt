package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.AnnotationConstantExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
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

    /** Whether [node] was written in the source: the compiler's own nodes have no position. */
    fun isSource(node: ASTNode): Boolean = node.lineNumber > 0

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

    /** The member names written in [annotations], nested annotations included: `@A(v = 1)` names `v`. */
    fun annotationMembers(annotations: Collection<AnnotationNode>): Set<String> {
        val found = HashSet<String>()
        fun collect(annotation: AnnotationNode) {
            found += annotation.members.keys
            annotation.members.values.forEach { value ->
                val nested = if (value is ListExpression) value.expressions else listOf(value)
                nested.filterIsInstance<AnnotationConstantExpression>().forEach { collect(it.value as AnnotationNode) }
            }
        }
        annotations.forEach(::collect)
        return found
    }

    /** The annotations on the declarations of [module] (the visitor reaches those inside method bodies). */
    fun declaredAnnotations(module: ModuleNode): List<AnnotationNode> {
        val methods = module.methods + module.classes.flatMap { it.methods }
        val parameters = methods.flatMap { it.parameters.toList() }
        val members = module.classes.flatMap { type ->
            type.annotations + type.fields.flatMap { it.annotations } +
                type.properties.flatMap { it.annotations + it.field.annotations }
        }
        return members + methods.flatMap { it.annotations } + parameters.flatMap { it.annotations } +
            module.`package`?.annotations.orEmpty()
    }
}
