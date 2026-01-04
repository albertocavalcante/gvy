package com.github.albertocavalcante.gvy.semantics

/**
 * Java/Groovy primitive types.
 *
 * Note: Order matters for numeric promotion (higher ordinal = wider type).
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
        get() = this in listOf(BYTE, CHAR, SHORT, INT, LONG)

    /**
     * Whether this is a floating-point type.
     */
    val isFloatingPoint: Boolean
        get() = this in listOf(FLOAT, DOUBLE)
}
