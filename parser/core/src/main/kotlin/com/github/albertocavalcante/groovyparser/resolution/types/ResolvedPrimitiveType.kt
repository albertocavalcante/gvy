package com.github.albertocavalcante.groovyparser.resolution.types

/**
 * Represents a resolved primitive type in the Groovy type system.
 *
 * Groovy supports all Java primitive types: boolean, char, byte, short, int, long, float, double.
 */
enum class ResolvedPrimitiveType(private val boxedName: String, private val numericPrecedence: Int) : ResolvedType {

    BOOLEAN("java.lang.Boolean", 0),
    CHAR("java.lang.Character", 1),
    BYTE("java.lang.Byte", 2),
    SHORT("java.lang.Short", 3),
    INT("java.lang.Integer", 4),
    LONG("java.lang.Long", 5),
    FLOAT("java.lang.Float", 6),
    DOUBLE("java.lang.Double", 7),
    ;

    override fun describe(): String = name.lowercase()

    override fun isPrimitive(): Boolean = true

    override fun asPrimitive(): ResolvedPrimitiveType = this

    override fun isAssignableBy(other: ResolvedType): Boolean = when {
        other == this -> true
        other.isPrimitive() -> isNumericAssignable(other.asPrimitive())
        else -> false
    }

    /**
     * Returns the fully qualified name of the boxed wrapper type.
     */
    fun box(): String = boxedName

    /**
     * Checks if a value of the other numeric type can be assigned to this type
     * without explicit casting (widening conversion).
     */
    private fun isNumericAssignable(other: ResolvedPrimitiveType): Boolean {
        // Boolean is not assignable from any other type
        if (this == BOOLEAN || other == BOOLEAN) {
            return this == other
        }

        // For numeric types, check widening conversion rules
        return when (this) {
            CHAR -> other == CHAR
            BYTE -> other == BYTE
            SHORT -> other == BYTE || other == SHORT
            INT -> other == BYTE || other == SHORT || other == INT || other == CHAR
            LONG -> other == BYTE || other == SHORT || other == INT || other == LONG || other == CHAR
            FLOAT -> other != DOUBLE && other != BOOLEAN
            DOUBLE -> other != BOOLEAN
            BOOLEAN -> false
        }
    }

    companion object {
        /**
         * Finds a primitive type by name (case-insensitive).
         *
         * @param name The name of the primitive type
         * @return The matching primitive type, or null if not found
         */
        fun byName(name: String): ResolvedPrimitiveType? = entries.find { it.name.equals(name, ignoreCase = true) }

        /**
         * All numeric primitive types (excluding boolean).
         */
        val NUMERIC_TYPES: List<ResolvedPrimitiveType> =
            listOf(BYTE, SHORT, INT, LONG, FLOAT, DOUBLE)

        /**
         * Promotes two numeric types to a common type for arithmetic operations.
         *
         * @param left The left operand type
         * @param right The right operand type
         * @return The promoted type
         */
        fun promoteNumericTypes(left: ResolvedPrimitiveType, right: ResolvedPrimitiveType): ResolvedPrimitiveType {
            if (left == BOOLEAN || right == BOOLEAN) {
                throw IllegalArgumentException("Cannot promote boolean types")
            }

            return when {
                left == DOUBLE || right == DOUBLE -> DOUBLE
                left == FLOAT || right == FLOAT -> FLOAT
                left == LONG || right == LONG -> LONG
                else -> INT // byte, short, char, int all promote to int
            }
        }
    }
}
