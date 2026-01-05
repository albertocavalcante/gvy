package com.github.albertocavalcante.groovyparser.resolution.typeinference

import com.github.albertocavalcante.groovyparser.ast.expr.ArrayExpr
import com.github.albertocavalcante.groovyparser.ast.expr.AttributeExpr
import com.github.albertocavalcante.groovyparser.ast.expr.BinaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.BitwiseNegationExpr
import com.github.albertocavalcante.groovyparser.ast.expr.CastExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ClassExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ClosureExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstantExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstructorCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.DeclarationExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ElvisExpr
import com.github.albertocavalcante.groovyparser.ast.expr.Expression
import com.github.albertocavalcante.groovyparser.ast.expr.GStringExpr
import com.github.albertocavalcante.groovyparser.ast.expr.LambdaExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ListExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MapEntryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MapExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodPointerExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodReferenceExpr
import com.github.albertocavalcante.groovyparser.ast.expr.NotExpr
import com.github.albertocavalcante.groovyparser.ast.expr.PostfixExpr
import com.github.albertocavalcante.groovyparser.ast.expr.PrefixExpr
import com.github.albertocavalcante.groovyparser.ast.expr.PropertyExpr
import com.github.albertocavalcante.groovyparser.ast.expr.RangeExpr
import com.github.albertocavalcante.groovyparser.ast.expr.SpreadExpr
import com.github.albertocavalcante.groovyparser.ast.expr.SpreadMapExpr
import com.github.albertocavalcante.groovyparser.ast.expr.TernaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.UnaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr
import com.github.albertocavalcante.groovyparser.resolution.Context
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.groovymodel.GroovyParserTypeResolver
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedArrayType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedNullType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedPrimitiveType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedReferenceType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType
import com.github.albertocavalcante.groovyparser.resolution.types.asReferenceType
import com.github.albertocavalcante.groovyparser.resolution.types.isReferenceType

/**
 * Extracts types from AST nodes through type inference.
 *
 * This class provides type inference for expressions by analyzing
 * their structure and context.
 */
class TypeExtractor(private val typeSolver: TypeSolver, private val context: Context) {

    /**
     * Infers the type of an expression.
     */
    /**
     * Infers the type of an expression.
     */
    fun extractType(expression: Expression): ResolvedType = when (expression) {
        is ConstantExpr -> extractConstant(expression)
        is VariableExpr -> extractVariable(expression)
        is MethodCallExpr -> extractMethodCall(expression)
        is PropertyExpr -> extractProperty(expression)
        is AttributeExpr -> objectType()
        is ClassExpr -> extractClassExpr(expression)
        is DeclarationExpr -> GroovyParserTypeResolver.resolveType(expression.type, typeSolver)
        is ConstructorCallExpr -> GroovyParserTypeResolver.resolveType(expression.typeName, typeSolver)

        // Grouped extractions
        is BinaryExpr, is UnaryExpr, is TernaryExpr, is CastExpr, is ElvisExpr,
        is PostfixExpr, is PrefixExpr, is NotExpr, is BitwiseNegationExpr,
        -> extractOperation(expression)

        is ListExpr, is MapExpr, is RangeExpr, is ArrayExpr, is SpreadExpr,
        is SpreadMapExpr, is MapEntryExpr,
        -> extractCollection(expression)

        is ClosureExpr, is LambdaExpr, is MethodPointerExpr, is MethodReferenceExpr,
        is GStringExpr,
        -> extractFunctionalOrString(expression)

        else -> objectType()
    }

    private fun extractOperation(expression: Expression): ResolvedType = when (expression) {
        is BinaryExpr -> extractBinary(expression)
        is UnaryExpr -> extractUnary(expression)
        is TernaryExpr -> extractTernary(expression)
        is CastExpr -> GroovyParserTypeResolver.resolveType(expression.targetType, typeSolver)
        is ElvisExpr -> extractType(expression.expression)
        is PostfixExpr -> extractType(expression.expression)
        is PrefixExpr -> extractType(expression.expression)
        is NotExpr -> ResolvedPrimitiveType.BOOLEAN
        is BitwiseNegationExpr -> extractType(expression.expression)
        else -> objectType()
    }

    private fun extractCollection(expression: Expression): ResolvedType = when (expression) {
        is ListExpr -> extractList(expression)
        is MapExpr -> extractMap(expression)
        is RangeExpr -> resolveType("groovy.lang.Range")
        is ArrayExpr -> ResolvedArrayType(GroovyParserTypeResolver.resolveType(expression.elementType, typeSolver))
        is SpreadExpr -> extractType(expression.expression)
        is SpreadMapExpr -> extractType(expression.expression)
        is MapEntryExpr -> objectType() // Should we have specific type for Map.Entry?
        else -> objectType()
    }

    private fun extractFunctionalOrString(expression: Expression): ResolvedType = when (expression) {
        is ClosureExpr -> resolveType("groovy.lang.Closure")
        is LambdaExpr -> resolveType("groovy.lang.Closure")
        is MethodPointerExpr -> resolveType("org.codehaus.groovy.runtime.MethodClosure")
        is MethodReferenceExpr -> resolveType("org.codehaus.groovy.runtime.MethodClosure")
        is GStringExpr -> resolveType("groovy.lang.GString")
        else -> objectType()
    }

    private fun extractConstant(node: ConstantExpr): ResolvedType = when (val value = node.value) {
        is Int -> ResolvedPrimitiveType.INT
        is Long -> ResolvedPrimitiveType.LONG
        is Double -> ResolvedPrimitiveType.DOUBLE
        is Float -> ResolvedPrimitiveType.FLOAT
        is Boolean -> ResolvedPrimitiveType.BOOLEAN
        is Char -> ResolvedPrimitiveType.CHAR
        is Byte -> ResolvedPrimitiveType.BYTE
        is Short -> ResolvedPrimitiveType.SHORT
        is String -> stringType()
        null -> ResolvedNullType
        is java.math.BigInteger -> resolveType("java.math.BigInteger")
        is java.math.BigDecimal -> resolveType("java.math.BigDecimal")
        else -> objectType()
    }

    private fun extractVariable(node: VariableExpr): ResolvedType {
        val ref = context.solveSymbol(node.name)
        return if (ref.isSolved) {
            ref.getDeclaration().type
        } else {
            objectType()
        }
    }

    private fun extractBinary(node: BinaryExpr): ResolvedType {
        val leftType = extractType(node.left)
        val rightType = extractType(node.right)
        return BinaryTypeLogic.extractBinary(node, leftType, rightType, typeSolver)
    }

    private fun extractMethodCall(node: MethodCallExpr): ResolvedType {
        val receiverType = if (node.objectExpression != null) {
            extractType(node.objectExpression)
        } else {
            context.solveSymbol("this").let {
                if (it.isSolved) it.getDeclaration().type else objectType()
            }
        }

        if (receiverType.isReferenceType()) {
            val methods = receiverType.asReferenceType().declaration.getDeclaredMethods()
            val method = methods.find { m ->
                m.name == node.methodName && m.getNumberOfParams() == node.arguments.size
            }
            if (method != null) {
                return method.returnType
            }
        }

        return objectType()
    }

    private fun extractProperty(node: PropertyExpr): ResolvedType {
        val objectType = extractType(node.objectExpression)

        if (objectType.isReferenceType()) {
            val fields = objectType.asReferenceType().declaration.getDeclaredFields()
            val field = fields.find { it.name == node.propertyName }
            if (field != null) {
                return field.type
            }

            val getterName = "get${node.propertyName.replaceFirstChar { it.uppercase() }}"
            val methods = objectType.asReferenceType().declaration.getDeclaredMethods()
            val getter = methods.find { it.name == getterName && it.getNumberOfParams() == 0 }
            if (getter != null) {
                return getter.returnType
            }
        }

        return objectType()
    }

    private fun extractList(node: ListExpr): ResolvedType {
        if (node.elements.isEmpty()) {
            return resolveType("java.util.ArrayList")
        }

        val elementTypes = node.elements.map { extractType(it) }
        val elementType = LeastUpperBoundLogic.lub(elementTypes, typeSolver)

        val listRef = typeSolver.tryToSolveType("java.util.ArrayList")
        return if (listRef.isSolved) {
            ResolvedReferenceType(listRef.getDeclaration(), listOf(elementType))
        } else {
            objectType()
        }
    }

    private fun extractMap(node: MapExpr): ResolvedType {
        if (node.entries.isEmpty()) {
            return resolveType("java.util.LinkedHashMap")
        }

        val keyTypes = node.entries.map { extractType(it.key) }
        val valueTypes = node.entries.map { extractType(it.value) }

        val keyType = LeastUpperBoundLogic.lub(keyTypes, typeSolver)
        val valueType = LeastUpperBoundLogic.lub(valueTypes, typeSolver)

        val mapRef = typeSolver.tryToSolveType("java.util.LinkedHashMap")
        return if (mapRef.isSolved) {
            ResolvedReferenceType(mapRef.getDeclaration(), listOf(keyType, valueType))
        } else {
            objectType()
        }
    }

    private fun extractTernary(node: TernaryExpr): ResolvedType {
        val thenType = extractType(node.trueExpression)
        val elseType = extractType(node.falseExpression)
        return LeastUpperBoundLogic.lub(listOf(thenType, elseType), typeSolver)
    }

    private fun extractUnary(node: UnaryExpr): ResolvedType {
        val exprType = extractType(node.expression)
        return when (node.operator) {
            "!", "not" -> ResolvedPrimitiveType.BOOLEAN
            "-", "+", "~" -> exprType
            else -> exprType
        }
    }

    private fun extractClassExpr(node: ClassExpr): ResolvedType {
        val ref = typeSolver.tryToSolveType("java.lang.Class")
        return if (ref.isSolved) {
            val innerTypeRef = typeSolver.tryToSolveType(node.className)
            val innerType = if (innerTypeRef.isSolved) {
                ResolvedReferenceType(innerTypeRef.getDeclaration())
            } else {
                objectType()
            }
            ResolvedReferenceType(ref.getDeclaration(), listOf(innerType))
        } else {
            objectType()
        }
    }

    // Helper methods

    private fun objectType(): ResolvedType {
        val ref = typeSolver.tryToSolveType("java.lang.Object")
        return if (ref.isSolved) ResolvedReferenceType(ref.getDeclaration()) else ResolvedPrimitiveType.INT
    }

    private fun stringType(): ResolvedType {
        val ref = typeSolver.tryToSolveType("java.lang.String")
        return if (ref.isSolved) ResolvedReferenceType(ref.getDeclaration()) else objectType()
    }

    private fun resolveType(name: String): ResolvedType {
        val ref = typeSolver.tryToSolveType(name)
        return if (ref.isSolved) ResolvedReferenceType(ref.getDeclaration()) else objectType()
    }
}
