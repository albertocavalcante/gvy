// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A use typed by a type parameter is not a use of the class its placeholder redirects to (the parameter's bound): the
 * index records the class's occurrences for real uses only (#2443).
 */
class TypeParameterOccurrencesTest : SymbolIdTestSupport() {
    private val source = """
        package pkg
        class T {}
        class Box<T extends pkg.T> {
            def a() { T.class }
            def b() { T.foo() }
            def c() { new T() }
            def d(x) { x instanceof T }
            static class In { def e() { T.class }; def f() { new T() } }
        }
        class Real { def g() { [T.class, new T()] } }
    """.trimIndent()

    @Test
    @DisplayName("a type parameter's class literal, call, constructor call and instanceof are not class occurrences")
    fun placeholderUsesAreNotClassOccurrences() {
        val classId = Symbol.ofClass("pkg.T").id

        val occurrences = build(source).occurrences
            .filter { it.symbol == classId && it.role != OccurrenceRole.DEFINITION }
            .map { "${it.role}@${it.range.startLine}:${it.range.startColumn}" }
            .sorted()

        // Only the static nested class (no parameter in scope) and `Real` use the class.
        // FIXME(#2459): the qualified bound `pkg.T` (2:24) is a use too, expected as TYPE_REF@2:24; the index does not
        // record it, as the AST scan does not report it either.
        assertEquals(
            listOf("CALL@7:57", "CALL@9:37", "TYPE_REF@7:32", "TYPE_REF@9:24"),
            occurrences,
        )
    }
}
