// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.net.URI

/** Builds semantic documents from fixtures at [phase], as production does at CANONICALIZATION. */
abstract class SymbolIdTestSupport {

    protected val uri = URI.create("file:///Test.groovy")

    /** The compile phase the AST reaches before indexing; production uses CANONICALIZATION. */
    protected open val phase: Int = Phases.CANONICALIZATION

    /**
     * Builds the document for [code] at [at]. Above CONVERSION the source is compiled to it and a failure
     * fails the test, unless the fixture names a class that is not in the source ([knownError]); such a
     * fixture must in fact fail, so the flag cannot go stale.
     */
    protected fun build(code: String, knownError: Boolean = false, at: Int = phase): SemanticDocument {
        if (at == Phases.CONVERSION) {
            val sourceUnit = SourceUnit("Test.groovy", code.trimIndent(), CompilerConfiguration(), null, null)
            sourceUnit.parse()
            sourceUnit.completePhase()
            sourceUnit.convert()
            return SemanticDocumentBuilder(sourceUnit.ast, uri).build()
        }
        val unit = CompilationUnit(CompilerConfiguration())
        unit.addSource("Test.groovy", code.trimIndent())
        val failure = runCatching { unit.compile(at) }.exceptionOrNull()
        if (knownError) {
            assertTrue(failure != null, "fixture is marked as a known compile error but compiled cleanly")
        } else {
            assertEquals(null, failure, "fixture must compile to phase $at")
            assertTrue(unit.phase >= at, "compile stopped at phase ${unit.phase}, before $phase")
        }
        // Like production, index whatever AST exists even when resolution reports errors (e.g. other files).
        return SemanticDocumentBuilder(unit.ast.modules.single(), uri).build()
    }

    protected fun SemanticDocument.calls(name: String) = occurrences.filter {
        it.role == OccurrenceRole.CALL && it.symbol.contains("#$name(")
    }

    /** The id of the one method definition `Cls#name(params).`, whatever the package. */
    protected fun SemanticDocument.def(cls: String, signature: String): String = symbols.single {
        it.kind == SymbolKind.METHOD && it.symbol.substringAfterLast('/') == "$cls#$signature."
    }.symbol
}
