// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Names inside closures are attributed only when every enclosing closure is passed through by a GDK method. */
class ClosureAttributionTest : SymbolIdTestSupport() {

    private fun SemanticDocument.references(symbolSuffix: String) =
        occurrences.filter { it.role == OccurrenceRole.REFERENCE && it.symbol.endsWith(symbolSuffix) }

    @Test
    @DisplayName("a field read inside each { } on a typed receiver is attributed")
    fun fieldInEach() {
        val doc = build("class C { String name; def run(List<String> xs) { xs.each { println name } } }")
        assertEquals(1, doc.references("C#name.").size)
    }

    @Test
    @DisplayName("an implicit-this call inside collect { } is attributed")
    fun callInCollect() {
        val doc = build(
            """
            class C {
                String helper(String s) { s }
                def run(List<String> xs) { xs.collect { helper(it) } }
            }
            """,
        )
        assertEquals(listOf(doc.def("C", "helper(String)")), doc.calls("helper").map { it.symbol })
    }

    @Test
    @DisplayName("nested transparent closures are attributed; an opaque outer closure makes the inner one opaque")
    fun nesting() {
        val transparent = build(
            "class C { String name; def run(List<String> xs, List<String> ys) { xs.each { ys.collect { name } } } }",
        )
        assertEquals(1, transparent.references("C#name.").size)
        val opaque = build(
            "class C { String name; def run(List<String> xs, Object cfg) { cfg.with { xs.each { name } } } }",
        )
        assertTrue(opaque.references("C#name.").isEmpty())
    }

    @Test
    @DisplayName("a list literal receiver is certain")
    fun listLiteral() {
        val doc = build("class C { String name; def run() { [1, 2].each { name } } }")
        assertEquals(1, doc.references("C#name.").size)
    }

    @Test
    @DisplayName("a receiver class declaring its own each(Closure) may set a delegate: not attributed")
    fun userDefinedEach() {
        val doc = build(
            """
            class Bag { def each(Closure c) { c.delegate = this; c() } }
            class C { String name; def run(Bag bag) { bag.each { name } } }
            """,
        )
        assertTrue(doc.references("C#name.").isEmpty())
    }

    @Test
    @DisplayName("an implicit-this each { } is not certain: not attributed")
    fun implicitThisEach() {
        val doc = build("class C { String name; def each(Closure c) { c() }; def run() { each { name } } }")
        assertTrue(doc.references("C#name.").isEmpty())
    }

    @Test
    @DisplayName("with and tap set the delegate: not attributed")
    fun withAndTap() {
        val doc = build(
            """
            class C {
                String name
                def run(List<String> xs) { xs.with { name }; xs.tap { name } }
            }
            """,
        )
        assertTrue(doc.references("C#name.").isEmpty())
    }

    @Test
    @DisplayName("a Jenkins-like DSL step inside a block is not attributed")
    fun dslStep() {
        val doc = build(
            """
            class C {
                void node(String label, Closure body) {}
                void sh(String cmd) {}
                void run() { node('x') { sh 'y' } }
            }
            """,
        )
        assertTrue(doc.calls("sh").isEmpty(), "${doc.calls("sh")}")
    }

    @Test
    @DisplayName("a name the closure answers itself (delegate, call) is not attributed")
    fun closureOwnMembers() {
        val doc = build(
            """
            class C {
                String owner
                def curry(x) { x }
                def run(List<String> xs) { xs.each { println owner; curry(it) } }
            }
            """,
        )
        assertTrue(doc.references("C#owner.").isEmpty())
        assertTrue(doc.calls("curry").isEmpty())
    }

    @Test
    @DisplayName("a subtype in the unit overriding each may set a delegate, so a closure on its supertype is opaque")
    fun subtypeOverridingEachMakesSupertypeReceiversOpaque() {
        val doc = build(
            """
            class Other { String name; def helper() {} }
            class Base implements Iterable { Iterator iterator() { [1].iterator() } }
            class Sub extends Base {
                def each(Closure c) { c.resolveStrategy = Closure.DELEGATE_FIRST; c.delegate = new Other(); c(1) }
            }
            class C {
                String name
                def helper() {}
                def viaBase(Base b) { b.each { name; helper() } }
                def viaIterable(Iterable o) { o.each { name } }
            }
            """,
        )
        assertEquals(emptyList<SymbolOccurrence>(), doc.references("C#name.") + doc.calls("helper"))
    }

    @Test
    @DisplayName("a closure that sets its own delegate or resolve strategy is opaque")
    fun closureChangingItsOwnResolutionIsOpaque() {
        val doc = build(
            """
            class Other { def helper() {} }
            class C {
                String name
                def helper() {}
                def run(List xs) {
                    xs.each { resolveStrategy = Closure.DELEGATE_ONLY; delegate = new Other(); helper(); name }
                }
            }
            """,
        )
        assertEquals(emptyList<SymbolOccurrence>(), doc.references("C#name.") + doc.calls("helper"))
    }

    @Test
    @DisplayName("a dispatching subtype in the unit may intercept each, so a closure on its supertype is opaque")
    fun interceptingSubtypeMakesSupertypeReceiversOpaque() {
        val doc = build(
            """
            class Other { String name }
            class X implements Iterable, GroovyInterceptable {
                Iterator iterator() { [1].iterator() }
                def invokeMethod(String n, args) { Closure c = args[0]; c.delegate = new Other(); c(1) }
            }
            class C {
                String name
                def run(Iterable o) { o.each { name } }
            }
            """,
        )
        assertEquals(emptyList<SymbolOccurrence>(), doc.references("C#name."))
    }

    @Test
    @DisplayName("a closure reaching its delegate through setProperty or invokeMethod is opaque")
    fun closureReshapingItselfReflectivelyIsOpaque() {
        val doc = build(
            """
            class Other { String name }
            class C {
                String name
                def a(List xs) { xs.each { setProperty('delegate', new Other()); name } }
                def b(List xs) { xs.each { invokeMethod('setDelegate', new Other()); name } }
            }
            """,
        )
        assertEquals(emptyList<SymbolOccurrence>(), doc.references("C#name."))
    }
}
