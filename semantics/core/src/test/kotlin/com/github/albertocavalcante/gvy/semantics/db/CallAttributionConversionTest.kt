// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Call attribution on an AST that stopped at CONVERSION, the rare last-resort fallback. Types there
 * are bare names the compiler has not resolved, and the indexer does not resolve them itself, so
 * only calls that need no type name are attributed.
 */
class CallAttributionConversionTest : SymbolIdTestSupport() {
    override val phase: Int = Phases.CONVERSION

    private val source = """
        package com.example
        class Base { void foo() {}; static void bar() {} }
        class A extends Base { void run() { foo(); this.foo() } }
        class Solo {
            void own() {}
            void run() { own(); this.own() }
        }
        class C {
            Base field
            void use(Base b, A a) {
                field.foo(); b.foo(); a.foo(); new A().foo(); Base.bar()
                Base typed = new Base(); typed.foo()
            }
        }
        class Outer {
            static class Inner { void inner() {} }
            Inner field
            void run() { field.inner(); Inner.inner() }
        }
        class Tree<Node> {
            Node root
            void run() { root.visit() }
        }
    """

    @Test
    @DisplayName("calls through a variable, field or parameter typed with another class are not attributed")
    fun typedReceiversAreNotAttributed() {
        val doc = build(source)
        for (name in listOf("foo", "bar", "inner", "visit")) {
            assertTrue(doc.calls(name).isEmpty(), "$name: ${doc.calls(name)}")
        }
    }

    @Test
    @DisplayName("this and implicit-this calls on a class whose hierarchy is itself and Object still resolve")
    fun thisCallsOnTheCurrentClassResolve() {
        val doc = build(source)
        assertEquals(List(2) { doc.def("Solo", "own()") }, doc.calls("own").map { it.symbol })
    }

    @Test
    @DisplayName("every call id equals the one the same source gets at CANONICALIZATION: never wrong")
    fun conversionNeverDiffersFromCanonicalization() {
        val conversion = build(source).occurrences.filter { it.role == OccurrenceRole.CALL }
        val canonical = build(source, at = Phases.CANONICALIZATION).occurrences
            .filter { it.role == OccurrenceRole.CALL }
            .map { it.range to it.symbol }
            .toSet()
        assertTrue(conversion.isNotEmpty())
        conversion.forEach { assertTrue((it.range to it.symbol) in canonical, "only at CONVERSION: $it") }
    }
}
