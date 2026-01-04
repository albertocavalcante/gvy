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

    /** `groovy.lang.GString` type. */
    val GSTRING = SemanticType.Known("groovy.lang.GString")

    /** `groovy.lang.Closure` type. */
    val CLOSURE = SemanticType.Known("groovy.lang.Closure")

    /** `groovy.lang.Range` type. */
    val RANGE = SemanticType.Known("groovy.lang.Range")

    // Collections

    /** `java.util.List` type. */
    val LIST = SemanticType.Known("java.util.List")

    /** `java.util.Set` type. */
    val SET = SemanticType.Known("java.util.Set")

    /** `java.util.Map` type. */
    val MAP = SemanticType.Known("java.util.Map")

    /** `java.util.Collection` type. */
    val COLLECTION = SemanticType.Known("java.util.Collection")

    /** `java.util.ArrayList` type. */
    val ARRAYLIST = SemanticType.Known("java.util.ArrayList")

    /** `java.util.HashMap` type. */
    val HASHMAP = SemanticType.Known("java.util.HashMap")

    /** `java.util.LinkedHashMap` type. */
    val LINKEDHASHMAP = SemanticType.Known("java.util.LinkedHashMap")

    // BigDecimal/BigInteger

    /** `java.math.BigDecimal` type. */
    val BIG_DECIMAL = SemanticType.Known("java.math.BigDecimal")

    /** `java.math.BigInteger` type. */
    val BIG_INTEGER = SemanticType.Known("java.math.BigInteger")

    // Primitives

    /** `boolean` primitive type. */
    val BOOLEAN = SemanticType.Primitive(PrimitiveKind.BOOLEAN)

    /** `byte` primitive type. */
    val BYTE = SemanticType.Primitive(PrimitiveKind.BYTE)

    /** `char` primitive type. */
    val CHAR = SemanticType.Primitive(PrimitiveKind.CHAR)

    /** `short` primitive type. */
    val SHORT = SemanticType.Primitive(PrimitiveKind.SHORT)

    /** `int` primitive type. */
    val INT = SemanticType.Primitive(PrimitiveKind.INT)

    /** `long` primitive type. */
    val LONG = SemanticType.Primitive(PrimitiveKind.LONG)

    /** `float` primitive type. */
    val FLOAT = SemanticType.Primitive(PrimitiveKind.FLOAT)

    /** `double` primitive type. */
    val DOUBLE = SemanticType.Primitive(PrimitiveKind.DOUBLE)

    // Special

    /** `void` primitive type. */
    val VOID = SemanticType.Primitive(PrimitiveKind.VOID)

    /** The singleton `null` type. */
    val NULL = SemanticType.Null

    /** Dynamic type (equivalent to `def` or untyped `Object`). */
    val DYNAMIC = SemanticType.Dynamic()
}
