package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.control.Phases

/**
 * The uniqueness suite again on ASTs compiled to CANONICALIZATION, the phase production indexes at.
 * The inherited `build` helper fails the test when a fixture does not compile to that phase, except
 * for fixtures that declare a known compile error (a supertype or callee that is not in the source).
 */
class SymbolIdUniquenessCanonicalizationTest : SymbolIdUniquenessTest() {
    override val phase: Int = Phases.CANONICALIZATION
}
