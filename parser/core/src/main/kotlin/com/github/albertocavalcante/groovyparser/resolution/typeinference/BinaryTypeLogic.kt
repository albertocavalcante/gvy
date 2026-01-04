package com.github.albertocavalcante.groovyparser.resolution.typeinference

import com.github.albertocavalcante.groovyparser.ast.expr.BinaryExpr
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedPrimitiveType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedReferenceType
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedType
import com.github.albertocavalcante.groovyparser.resolution.types.asPrimitive
import com.github.albertocavalcante.groovyparser.resolution.types.asReferenceType
import com.github.albertocavalcante.groovyparser.resolution.types.isPrimitive
import com.github.albertocavalcante.groovyparser.resolution.types.isReferenceType

internal object BinaryTypeLogic {

    fun extractBinary(
        node: BinaryExpr,
        leftType: ResolvedType,
        rightType: ResolvedType,
        typeSolver: TypeSolver,
    ): ResolvedType = when (node.operator) {
        "==", "!=", "<", ">", "<=", ">=", "===", "!==",
        "instanceof", "in", "<=>", "=~", "==~",
        -> ResolvedPrimitiveType.BOOLEAN

        "&&", "||" -> ResolvedPrimitiveType.BOOLEAN
        "&", "|", "^" -> extractBitwise(leftType, rightType, typeSolver)
        "+", "-", "*", "/", "%" -> extractArithmetic(node.operator, leftType, rightType, typeSolver)
        "**" -> extractPower(leftType, rightType, typeSolver)
        "<<", ">>", ">>>" -> extractShift(leftType)
        "?:" -> leftType
        "..", "..<" -> resolveType("groovy.lang.Range", typeSolver)
        "=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=" -> leftType
        else -> objectType(typeSolver)
    }

    private fun extractBitwise(leftType: ResolvedType, rightType: ResolvedType, typeSolver: TypeSolver): ResolvedType =
        if (leftType == ResolvedPrimitiveType.BOOLEAN && rightType == ResolvedPrimitiveType.BOOLEAN) {
            ResolvedPrimitiveType.BOOLEAN
        } else {
            inferArithmeticType(leftType, rightType, typeSolver)
        }

    private fun extractArithmetic(
        operator: String,
        leftType: ResolvedType,
        rightType: ResolvedType,
        typeSolver: TypeSolver,
    ): ResolvedType = if (operator == "+" && (isString(leftType) || isString(rightType))) {
        resolveType("java.lang.String", typeSolver)
    } else {
        inferArithmeticType(leftType, rightType, typeSolver)
    }

    private fun extractShift(leftType: ResolvedType): ResolvedType = if (leftType == ResolvedPrimitiveType.LONG) {
        ResolvedPrimitiveType.LONG
    } else {
        ResolvedPrimitiveType.INT
    }

    private fun inferArithmeticType(left: ResolvedType, right: ResolvedType, typeSolver: TypeSolver): ResolvedType {
        if (left.isPrimitive() && right.isPrimitive()) {
            return NumericLubLogic.binaryNumericPromotion(
                left.asPrimitive(),
                right.asPrimitive(),
            )
        }

        if (isBigDecimal(left) || isBigDecimal(right)) {
            return resolveType("java.math.BigDecimal", typeSolver)
        }
        if (isBigInteger(left) || isBigInteger(right)) {
            return resolveType("java.math.BigInteger", typeSolver)
        }

        return ResolvedPrimitiveType.DOUBLE
    }

    private fun extractPower(leftType: ResolvedType, rightType: ResolvedType, typeSolver: TypeSolver): ResolvedType {
        // For integer bases with non-negative integer exponents, result is typically BigInteger or Integer
        // For floating point or negative exponents, result is typically Double or BigDecimal
        if (leftType.isPrimitive() && rightType.isPrimitive()) {
            val left = leftType.asPrimitive()
            val right = rightType.asPrimitive()

            // If either is floating point, result is Double
            if (left == ResolvedPrimitiveType.FLOAT || left == ResolvedPrimitiveType.DOUBLE ||
                right == ResolvedPrimitiveType.FLOAT || right == ResolvedPrimitiveType.DOUBLE
            ) {
                return ResolvedPrimitiveType.DOUBLE
            }
        }

        // Default to BigDecimal for general power operations
        return resolveType("java.math.BigDecimal", typeSolver)
    }

    private fun isString(type: ResolvedType): Boolean = type.isReferenceType() &&
        type.asReferenceType().declaration.qualifiedName == "java.lang.String"

    private fun isBigDecimal(type: ResolvedType): Boolean = type.isReferenceType() &&
        type.asReferenceType().declaration.qualifiedName == "java.math.BigDecimal"

    private fun isBigInteger(type: ResolvedType): Boolean = type.isReferenceType() &&
        type.asReferenceType().declaration.qualifiedName == "java.math.BigInteger"

    private fun objectType(typeSolver: TypeSolver): ResolvedType {
        val ref = typeSolver.tryToSolveType("java.lang.Object")
        return if (ref.isSolved) ResolvedReferenceType(ref.getDeclaration()) else ResolvedPrimitiveType.INT
    }

    private fun resolveType(name: String, typeSolver: TypeSolver): ResolvedType {
        val ref = typeSolver.tryToSolveType(name)
        return if (ref.isSolved) ResolvedReferenceType(ref.getDeclaration()) else objectType(typeSolver)
    }
}
