package com.github.albertocavalcante.gvy.semantics.dsl

sealed interface DslElement {
    data class Method(val name: String, val doc: String, val nested: List<DslElement>) : DslElement
    data class Parameter(val name: String, val type: String, val doc: String) : DslElement
    data class Block(val name: String, val doc: String, val elements: List<DslElement>) : DslElement
}

data class DslGrammar(val name: String, val rootElements: List<DslElement>)
