package com.github.albertocavalcante.gvy.semantics.dsl

/**
 * Represents a structural element in a DSL grammar definition.
 *
 * DSL grammars are hierarchical specifications that describe the allowed
 * constructs in a domain-specific language. Each [DslElement] represents
 * a distinct syntactic construct, such as a method call, a parameter, or
 * a block construct.
 *
 * This sealed interface provides three concrete types:
 * - [Method]: Represents a callable method or function in the DSL
 * - [Parameter]: Represents a typed parameter or argument
 * - [Block]: Represents a structural block that can contain nested elements
 *
 * These elements can be composed hierarchically to express complex DSL
 * structures, such as Jenkins Pipeline syntax or Gradle build scripts.
 *
 * ### Example
 *
 * ```kotlin
 * val stageElement = DslElement.Method(
 *     name = "stage",
 *     doc = "Defines a stage in the pipeline",
 *     nested = listOf(
 *         DslElement.Block(
 *             name = "steps",
 *             doc = "Contains the steps to execute",
 *             elements = emptyList()
 *         )
 *     )
 * )
 * ```
 */
sealed interface DslElement {
    /**
     * Represents a callable method or function in the DSL.
     *
     * @property name The method name as it appears in DSL code
     * @property doc Human-readable documentation describing the method's purpose
     * @property nested Child elements that can appear within this method's scope
     */
    data class Method(val name: String, val doc: String, val nested: List<DslElement>) : DslElement

    /**
     * Represents a typed parameter or argument in the DSL.
     *
     * @property name The parameter name
     * @property type The expected type (e.g., "String", "Closure")
     * @property doc Human-readable documentation describing the parameter
     */
    data class Parameter(val name: String, val type: String, val doc: String) : DslElement

    /**
     * Represents a structural block that can contain nested DSL elements.
     *
     * Blocks are typically used for grouping related constructs, such as
     * a "stages" block in Jenkins Pipeline or a "dependencies" block in Gradle.
     *
     * @property name The block name as it appears in DSL code
     * @property doc Human-readable documentation describing the block's purpose
     * @property elements Child elements that can appear within this block
     */
    data class Block(val name: String, val doc: String, val elements: List<DslElement>) : DslElement
}

/**
 * A complete grammar definition for a domain-specific language.
 *
 * A [DslGrammar] captures the hierarchical structure of a DSL by defining
 * its top-level constructs (the [rootElements]). Each root element is a
 * [DslElement] that may have nested children, forming a tree structure.
 *
 * This representation is used by the semantics layer to validate DSL code,
 * provide IDE support (code completion, documentation), and enable analysis
 * tools to understand DSL patterns.
 *
 * ### Example
 *
 * ```kotlin
 * val jenkinsGrammar = DslGrammar(
 *     name = "Jenkins Pipeline",
 *     rootElements = listOf(
 *         DslElement.Method(
 *             name = "pipeline",
 *             doc = "Defines a declarative pipeline",
 *             nested = listOf(...)
 *         )
 *     )
 * )
 * ```
 *
 * @property name The name of the DSL (e.g., "Jenkins Pipeline", "Gradle Kotlin DSL")
 * @property rootElements The top-level elements that can appear in this DSL
 */
data class DslGrammar(val name: String, val rootElements: List<DslElement>)
