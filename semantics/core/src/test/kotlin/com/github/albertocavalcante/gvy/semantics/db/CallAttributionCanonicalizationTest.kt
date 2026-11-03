package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.control.Phases

/** The call-attribution suite on ASTs compiled to CANONICALIZATION, the phase production indexes at. */
class CallAttributionCanonicalizationTest : CallAttributionTest() {
    override val phase: Int = Phases.CANONICALIZATION
}
