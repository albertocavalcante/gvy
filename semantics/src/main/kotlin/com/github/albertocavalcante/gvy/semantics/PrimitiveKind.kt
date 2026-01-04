package com.github.albertocavalcante.gvy.semantics

/**
 * Kinds of primitive types supported by the language.
 * Note: Numeric promotion precedence is defined in TypeLub.getNumericPrecedence, not by enum ordinal.
 */
enum class PrimitiveKind {
    /** Boolean value (true/false). */
    BOOLEAN,

    /** 8-bit signed integer. */
    BYTE,

    /** 16-bit Unicode character. */
    CHAR,

    /** 16-bit signed integer. */
    SHORT,

    /** 32-bit signed integer. */
    INT,

    /** 64-bit signed integer. */
    LONG,

    /** 32-bit floating point. */
    FLOAT,

    /** 64-bit floating point. */
    DOUBLE,
    ;

    /**
     * Whether this is a numeric type (can participate in arithmetic).
     */
    val isNumeric: Boolean
        get() = this != BOOLEAN

    /**
     * Whether this is an integral type (no fractional part).
     */
    val isIntegral: Boolean
        get() = when (this) {
            BYTE, CHAR, SHORT, INT, LONG -> true
            else -> false
        }

    /**
     * Whether this is a floating-point type.
     */
    val isFloatingPoint: Boolean
        get() = this == FLOAT || this == DOUBLE
}
