package com.github.albertocavalcante.gvy.semantics

/**
 * Common SemanticType constants for frequently used types.
 */
object TypeConstants {
    // Java types
    /** `java.lang.Object` type. */
    val OBJECT = SemanticType.Known("java.lang.Object")

    /** `java.lang.String` type. */
    val STRING = SemanticType.Known("java.lang.String")

    /** `java.lang.Class` type. */
    val CLASS = SemanticType.Known("java.lang.Class")

    /** `java.lang.Number` type. */
    val NUMBER = SemanticType.Known("java.lang.Number")

    /** `java.lang.Comparable` type. */
    val COMPARABLE = SemanticType.Known("java.lang.Comparable")

    /** `java.lang.CharSequence` type. */
    val CHARSEQUENCE = SemanticType.Known("java.lang.CharSequence")

    /** `java.lang.Iterable` type. */
    val ITERABLE = SemanticType.Known("java.lang.Iterable")

    // Groovy types
    val GSTRING = SemanticType.Known("groovy.lang.GString")
    val CLOSURE = SemanticType.Known("groovy.lang.Closure")
    val RANGE = SemanticType.Known("groovy.lang.Range")

    // Collections
    val LIST = SemanticType.Known("java.util.List")
    val SET = SemanticType.Known("java.util.Set")
    val MAP = SemanticType.Known("java.util.Map")
    val COLLECTION = SemanticType.Known("java.util.Collection")
    val ARRAYLIST = SemanticType.Known("java.util.ArrayList")
    val HASHMAP = SemanticType.Known("java.util.HashMap")
    val LINKEDHASHMAP = SemanticType.Known("java.util.LinkedHashMap")

    // BigDecimal/BigInteger
    val BIG_DECIMAL = SemanticType.Known("java.math.BigDecimal")
    val BIG_INTEGER = SemanticType.Known("java.math.BigInteger")

    // Primitives
    val BOOLEAN = SemanticType.Primitive(PrimitiveKind.BOOLEAN)
    val BYTE = SemanticType.Primitive(PrimitiveKind.BYTE)
    val CHAR = SemanticType.Primitive(PrimitiveKind.CHAR)
    val SHORT = SemanticType.Primitive(PrimitiveKind.SHORT)
    val INT = SemanticType.Primitive(PrimitiveKind.INT)
    val LONG = SemanticType.Primitive(PrimitiveKind.LONG)
    val FLOAT = SemanticType.Primitive(PrimitiveKind.FLOAT)
    val DOUBLE = SemanticType.Primitive(PrimitiveKind.DOUBLE)

    // Special

    /** `void` type (represented as Known type "void"). */
    val VOID = SemanticType.Known("void")

    /** The singleton `null` type. */
    val NULL = SemanticType.Null

    /** Dynamic type (equivalent to `def` or untyped `Object`). */
    val DYNAMIC = SemanticType.Dynamic()
}
