package com.github.albertocavalcante.gvy.semantics.dsl

/**
 * Reference implementation of a [DslGrammar] for Jenkins Declarative Pipeline.
 *
 * This object provides a hierarchical grammar definition for Jenkins Pipeline
 * syntax, demonstrating how to model a real-world DSL using the [DslElement]
 * and [DslGrammar] types. It serves both as a concrete example for users of
 * this framework and as a basis for building Jenkins Pipeline tooling.
 *
 * The grammar captures the key constructs of Jenkins Declarative Pipeline:
 * - `pipeline`: The top-level block that defines the entire pipeline
 * - `agent`: Specifies where the pipeline (or a stage) should execute
 * - `stages`: Contains one or more stage blocks
 * - `stage`: Represents a distinct phase in the pipeline (e.g., Build, Test)
 * - `steps`: Contains the actual commands to execute within a stage
 *
 * This is a simplified representation focused on demonstrating the framework.
 * A production-grade Jenkins grammar would include additional constructs such
 * as `post`, `environment`, `options`, `parameters`, and various step types.
 *
 * ### Example usage
 *
 * ```kotlin
 * val grammar = JenkinsPipelineDsl.grammar
 * println(grammar.name) // "Jenkins Pipeline"
 * val pipelineElement = grammar.rootElements.first() as DslElement.Method
 * println(pipelineElement.name) // "pipeline"
 * ```
 *
 * @see DslGrammar
 * @see DslElement
 */
object JenkinsPipelineDsl {
    val grammar = DslGrammar(
        name = "Jenkins Pipeline",
        rootElements = listOf(
            DslElement.Method(
                "pipeline",
                "Defines a Jenkins pipeline",
                listOf(
                    DslElement.Block(
                        "agent",
                        "Specifies where pipeline runs",
                        listOf(
                            DslElement.Parameter("label", "String", "Node label"),
                            DslElement.Method("any", "Run on any available agent", emptyList()),
                        ),
                    ),
                    DslElement.Block(
                        "stages",
                        "Contains pipeline stages",
                        listOf(
                            DslElement.Block(
                                "stage",
                                "A single stage",
                                listOf(
                                    DslElement.Block("steps", "Steps to execute", emptyList()),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )
}
