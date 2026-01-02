package com.github.albertocavalcante.groovyparser

/**
 * Represents a Groovy language version level.
 * Used to configure the parser for specific Groovy version compatibility.
 */
enum class GroovyLanguageLevel(val version: String, val features: Set<Feature>) {
    /** Groovy 2.4 - Used by Jenkins pipelines */
    GROOVY_2_4("2.4", setOf(Feature.CLOSURES, Feature.TRAITS)),

    /** Groovy 2.5 - Macro support */
    GROOVY_2_5("2.5", setOf(Feature.CLOSURES, Feature.TRAITS, Feature.MACROS)),

    /** Groovy 3.0 - Lambda syntax, method references */
    GROOVY_3_0(
        "3.0",
        setOf(Feature.CLOSURES, Feature.TRAITS, Feature.MACROS, Feature.LAMBDAS, Feature.METHOD_REFS),
    ),

    /** Groovy 4.0 - Sealed classes, records, switch expressions */
    GROOVY_4_0(
        "4.0",
        setOf(
            Feature.CLOSURES,
            Feature.TRAITS,
            Feature.MACROS,
            Feature.LAMBDAS,
            Feature.METHOD_REFS,
            Feature.SEALED_CLASSES,
            Feature.RECORDS,
            Feature.SWITCH_EXPRESSIONS,
        ),
    ),

    /** Groovy 5.0 - Bleeding edge */
    GROOVY_5_0(
        "5.0",
        setOf(
            Feature.CLOSURES,
            Feature.TRAITS,
            Feature.MACROS,
            Feature.LAMBDAS,
            Feature.METHOD_REFS,
            Feature.SEALED_CLASSES,
            Feature.RECORDS,
            Feature.SWITCH_EXPRESSIONS,
        ),
    ),
    ;

    companion object {
        /** Language level for Jenkins pipelines (locked to Groovy 2.4) */
        val JENKINS = GROOVY_2_4

        /** Language level for Gradle 7.x */
        val GRADLE_7 = GROOVY_3_0

        /** Language level for Gradle 8.x */
        val GRADLE_8 = GROOVY_4_0

        /** The most commonly used language level */
        val POPULAR = GROOVY_4_0

        /** The current stable language level */
        val CURRENT = GROOVY_4_0
    }
}

/**
 * Language features that may or may not be available depending on language level.
 */
enum class Feature {
    CLOSURES,
    TRAITS,
    MACROS,
    LAMBDAS,
    METHOD_REFS,
    SEALED_CLASSES,
    RECORDS,
    SWITCH_EXPRESSIONS,
}
