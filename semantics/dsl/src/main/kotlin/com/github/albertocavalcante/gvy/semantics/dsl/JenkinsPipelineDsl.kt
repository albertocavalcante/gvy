package com.github.albertocavalcante.gvy.semantics.dsl

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
