package com.github.albertocavalcante.groovyparser.api

/**
 * Compiler policy shared by every engine that compiles user code only to analyse it.
 */
object AnalysisCompilerPolicy {
    /**
     * Global AST transformations that must never run during analysis, even when the compiler's own class
     * loader can see them.
     *
     * - Spock's transform rewrites feature methods and drops the block labels the language server reads.
     * - `@Grab` resolves dependencies through Ivy (not shipped with the server, so it throws
     *   `NoClassDefFoundError`) and would download artifacts just because a file was opened.
     */
    val DISABLED_GLOBAL_TRANSFORMS: Set<String> = setOf(
        "org.spockframework.compiler.SpockTransform",
        "groovy.grape.GrabAnnotationTransformation",
    )
}
