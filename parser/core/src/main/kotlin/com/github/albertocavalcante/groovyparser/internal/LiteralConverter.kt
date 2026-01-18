package com.github.albertocavalcante.groovyparser.internal

import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.expr.ArrayExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstantExpr
import com.github.albertocavalcante.groovyparser.ast.expr.Expression
import com.github.albertocavalcante.groovyparser.ast.expr.GStringExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ListExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MapEntryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MapExpr
import com.github.albertocavalcante.groovyparser.ast.expr.RangeExpr
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.expr.ArrayExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.RangeExpression

/**
 * Converts literal value expressions (strings, numbers, lists, maps, ranges).
 *
 * Handles ~130 lines of literal-specific conversion logic.
 */
internal class LiteralConverter(private val setRange: (Node, ASTNode) -> Unit) {

    /**
     * Converts a Groovy constant expression (primitive values, strings, null).
     */
    fun convertConstant(expr: ConstantExpression): ConstantExpr {
        val constant = ConstantExpr(expr.value)
        setRange(constant, expr)
        return constant
    }

    /**
     * Converts a Groovy GString expression (interpolated string).
     */
    fun convertGString(
        expr: GStringExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): GStringExpr {
        val gstring = GStringExpr()

        // Add string parts
        expr.strings?.forEach { str ->
            if (str is ConstantExpression) {
                gstring.addString(str.value?.toString() ?: "")
            }
        }

        // Add expressions
        expr.values?.forEach { value ->
            gstring.addExpression(convertExpr(value))
        }

        setRange(gstring, expr)
        return gstring
    }

    /**
     * Converts a Groovy list expression.
     */
    fun convertList(
        expr: ListExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): ListExpr {
        val list = ListExpr()
        expr.expressions?.forEach { element ->
            list.addElement(convertExpr(element))
        }
        setRange(list, expr)
        return list
    }

    /**
     * Converts a Groovy map expression.
     */
    fun convertMap(expr: MapExpression, convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression): MapExpr {
        val map = MapExpr()
        expr.mapEntryExpressions?.forEach { entry ->
            val key = convertExpr(entry.keyExpression)
            val value = convertExpr(entry.valueExpression)
            val mapEntry = MapEntryExpr(key, value)
            setRange(mapEntry, entry)
            map.addEntry(mapEntry)
        }
        setRange(map, expr)
        return map
    }

    /**
     * Converts a Groovy range expression (e.g., 1..10, 'a'..'z').
     */
    fun convertRange(
        expr: RangeExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): RangeExpr {
        val from = convertExpr(expr.from)
        val to = convertExpr(expr.to)
        val range = RangeExpr(from, to, expr.isInclusive)
        setRange(range, expr)
        return range
    }

    /**
     * Converts a Groovy array expression.
     */
    fun convertArray(
        expr: ArrayExpression,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ): ArrayExpr {
        val elementType = expr.elementType?.name ?: "Object"
        val sizes = expr.sizeExpression?.map { convertExpr(it) } ?: emptyList()
        val inits = expr.expressions?.map { convertExpr(it) } ?: emptyList()
        val array = ArrayExpr(elementType, sizes, inits)
        setRange(array, expr)
        return array
    }
}
