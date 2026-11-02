package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.control.Phases

/** The uniqueness suite again on ASTs compiled to CANONICALIZATION, the phase production indexes at. */
class SymbolIdUniquenessCanonicalizationTest : SymbolIdUniquenessTest() {
    override val phase: Int = Phases.CANONICALIZATION
}
