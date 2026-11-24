// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Constructors are declarations with ids `<class>#<init>(descriptor).`, and a `new T(args)` records a call to the
 * constructor when exactly one declared constructor can take it. The class occurrence at the type name stays, so
 * the class's references keep finding `new T()`; the constructor's occurrence covers `new T` and so is the
 * innermost only where the cursor is on `new`.
 */
class ConstructorIndexingTest : SymbolIdTestSupport() {

    private fun SemanticDocument.constructors() = symbols.filter { it.kind == SymbolKind.CONSTRUCTOR }

    private fun SemanticDocument.constructorCalls() =
        occurrences.filter { it.role == OccurrenceRole.CALL && it.symbol.contains("#<init>(") }

    private fun SemanticDocument.classCalls(classId: String) =
        occurrences.filter { it.role == OccurrenceRole.CALL && it.symbol == classId }

    @Test
    @DisplayName("an explicit constructor is a definition named for its class, owned by the class")
    fun explicitConstructorIsDefined() {
        val doc = build("package p\nclass Foo {\n    Foo(String name) { }\n}")

        val constructor = doc.constructors().single()
        assertEquals("p/Foo#<init>(String).", constructor.symbol)
        assertEquals("Foo", constructor.name)
        assertEquals("p/Foo#", constructor.owner)
        val definition = doc.occurrences.single { it.symbol == constructor.symbol }
        assertEquals(OccurrenceRole.DEFINITION, definition.role)
        assertEquals(constructor.range, definition.range)
    }

    @Test
    @DisplayName("constructor parameters are defined, so uses of them in the body have a declaration")
    fun constructorParametersAreDefined() {
        val doc = build("class Foo {\n    String n\n    Foo(String name) { n = name }\n}")

        val parameter = doc.symbols.single { it.kind == SymbolKind.PARAMETER && it.name == "name" }
        assertEquals("Foo#<init>(String).(name)", parameter.symbol)
        assertEquals("Foo#<init>(String).", parameter.owner)
        assertTrue(doc.occurrences.any { it.symbol == parameter.symbol && it.role == OccurrenceRole.REFERENCE })
    }

    @Test
    @DisplayName("overloaded constructors have distinct ids and calls pick the one that can take the arguments")
    fun overloadsAreDistinguishedByArity() {
        val doc = build(
            """
            class Foo {
                Foo() { }
                Foo(String a) { }
                Foo(String a, int b) { }
            }
            class Use {
                def run() { [new Foo(), new Foo('a'), new Foo('a', 1)] }
            }
            """,
        )

        assertEquals(
            setOf("Foo#<init>().", "Foo#<init>(String).", "Foo#<init>(String,int)."),
            doc.constructors().map { it.symbol }.toSet(),
        )
        val calls = doc.constructorCalls().sortedBy { it.range.startColumn }
        assertEquals(
            listOf("Foo#<init>().", "Foo#<init>(String).", "Foo#<init>(String,int)."),
            calls.map { it.symbol },
        )
    }

    @Test
    @DisplayName("a call that two constructors of the same arity could take stays with the class")
    fun sameArityOverloadsAreAmbiguous() {
        val doc = build(
            """
            class Foo {
                Foo(String a) { }
                Foo(int a) { }
            }
            class Use { def run() { new Foo('a') } }
            """,
        )

        assertEquals(2, doc.constructors().size)
        assertEquals(emptyList<SymbolOccurrence>(), doc.constructorCalls())
        assertEquals(1, doc.classCalls("Foo#").size)
    }

    @Test
    @DisplayName("default parameters and varargs widen the arity a constructor takes")
    fun defaultsAndVarargsWidenArity() {
        val doc = build(
            """
            class Foo { Foo(String a, int b = 1) { } }
            class Bar { Bar(String... rest) { } }
            class Use { def run() { [new Foo('a'), new Foo('a', 2), new Bar(), new Bar('a', 'b')] } }
            """,
        )

        assertEquals(
            listOf(
                "Foo#<init>(String,int).",
                "Foo#<init>(String,int).",
                "Bar#<init>(String[]).",
                "Bar#<init>(String[]).",
            ),
            doc.constructorCalls().sortedBy { it.range.startColumn }.map { it.symbol },
        )
    }

    @Test
    @DisplayName("the class occurrence at the type name is kept; the constructor's covers `new Foo`")
    fun classOccurrenceIsKeptAndRangesNest() {
        val source = "class Foo { Foo(String a) { } }\ndef x = new Foo('a')"
        val doc = build(source)
        val line = source.lines()[1]

        val classCall = doc.classCalls("Foo#").single()
        val constructorCall = doc.constructorCalls().single()
        assertEquals("Foo", line.substring(classCall.range.startColumn, classCall.range.endColumn))
        assertEquals("new Foo", line.substring(constructorCall.range.startColumn, constructorCall.range.endColumn))
        assertTrue(constructorCall.range.contains(classCall.range.startLine, classCall.range.startColumn))
        assertTrue(
            doc.occurrences.filter { it.range == classCall.range }.size == 1,
            "no two occurrences share the type-name range, so the innermost occurrence there is unambiguous",
        )
    }

    @Test
    @DisplayName("a class with no declared constructor has no constructor symbol; calls stay with the class")
    fun implicitDefaultConstructorIsNotIndexed() {
        val doc = build("class Foo { }\nclass Use { def run() { new Foo() } }")

        assertEquals(emptyList<SymbolInfo>(), doc.constructors())
        assertEquals(emptyList<SymbolOccurrence>(), doc.constructorCalls())
        assertEquals(1, doc.classCalls("Foo#").size)
    }

    @Test
    @DisplayName("a call with no matching arity stays with the class")
    fun noCandidateStaysWithClass() {
        val doc = build("class Foo { Foo(String a) { } }\nclass Use { def run() { new Foo() } }")

        assertEquals(emptyList<SymbolOccurrence>(), doc.constructorCalls())
        assertEquals(1, doc.classCalls("Foo#").size)
    }

    @Test
    @DisplayName("named-argument construction may use the map constructor, so it is not attributed to a constructor")
    fun namedArgumentsAreNotAttributed() {
        val doc = build(
            """
            class Foo { String name; Foo() { }; Foo(Object o) { } }
            class Use { def run() { new Foo(name: 'x') } }
            """,
        )

        assertEquals(emptyList<SymbolOccurrence>(), doc.constructorCalls())
        assertEquals(1, doc.classCalls("Foo#").size)
        assertTrue("name" in doc.unresolvedNames)
    }

    @Test
    @DisplayName("a spread argument has an unknown arity, so it is not attributed")
    fun spreadArgumentsAreNotAttributed() {
        val doc = build("class Foo { Foo(String a) { } }\nclass Use { def run(l) { new Foo(*l) } }")

        assertEquals(emptyList<SymbolOccurrence>(), doc.constructorCalls())
    }

    @Test
    @DisplayName("constructors generated by a transform are not indexed and make calls unattributed")
    fun generatedConstructorsAreUnresolved() {
        val doc = build(
            """
            @groovy.transform.TupleConstructor(force = true)
            class Foo {
                String a
                Foo(String a, int b) { }
            }
            class Use { def run() { new Foo('a') } }
            """,
        )

        assertTrue(doc.constructors().all { it.symbol == "Foo#<init>(String,int)." }, "Got: ${doc.constructors()}")
        assertEquals(emptyList<SymbolOccurrence>(), doc.constructorCalls())
        assertEquals(1, doc.classCalls("Foo#").size)
    }

    @Test
    @DisplayName("@Canonical and @MapConstructor classes are not attributed to a constructor")
    fun canonicalIsUnresolved() {
        val doc = build(
            """
            @groovy.transform.Canonical
            class Foo { String a }
            @groovy.transform.MapConstructor
            class Bar { String a; Bar(String a, String b) { } }
            class Use { def run() { [new Foo('a'), new Bar('a', 'b')] } }
            """,
        )

        assertEquals(emptyList<SymbolOccurrence>(), doc.constructorCalls())
    }

    @Test
    @DisplayName("this(...) and super(...) are not attributed to a constructor")
    fun specialCallsAreNotAttributed() {
        val doc = build(
            """
            class Base { Base(String a) { } }
            class Foo extends Base {
                Foo() { this('x') }
                Foo(String a) { super(a) }
            }
            """,
        )

        assertEquals(emptyList<SymbolOccurrence>(), doc.constructorCalls())
    }

    @Test
    @DisplayName("an anonymous class is not attributed to a constructor and has no constructor symbol")
    fun anonymousClassIsNotAttributed() {
        val doc = build(
            """
            abstract class Base { Base(String a) { } }
            class Use { def run() { new Base('a') { } } }
            """,
        )

        assertEquals(1, doc.constructors().size)
        assertEquals(emptyList<SymbolOccurrence>(), doc.constructorCalls())
    }

    @Test
    @DisplayName("enum constructors take hidden compiler parameters, so enums have no constructor symbols")
    fun enumsAreNotIndexed() {
        val doc = build("enum Color {\n    RED(1)\n    Color(int v) { }\n}")

        assertEquals(emptyList<SymbolInfo>(), doc.constructors())
        assertEquals(emptyList<SymbolOccurrence>(), doc.constructorCalls())
    }

    @Test
    @DisplayName("a constructor of a class in the same unit is called from another class of it")
    fun callsAcrossClassesOfTheUnit() {
        val doc = build(
            """
            package p
            class Outer {
                static class Nested { Nested(int x) { } }
                def run() { new Nested(1) }
            }
            """,
        )

        assertEquals(
            listOf("p/Outer\$Nested#<init>(int)."),
            doc.constructorCalls().map { it.symbol },
        )
        assertEquals("p/Outer\$Nested#<init>(int).", doc.constructors().single().symbol)
    }

    @Test
    @DisplayName("a non-static member class takes the outer instance as a hidden parameter, so it is not indexed")
    fun innerClassIsNotIndexed() {
        val doc = build(
            """
            class Outer {
                class Inner { Inner(int x) { } }
                def run() { new Inner(1) }
            }
            """,
        )

        assertEquals(emptyList<SymbolInfo>(), doc.constructors())
        assertEquals(emptyList<SymbolOccurrence>(), doc.constructorCalls())
        assertEquals(1, doc.classCalls("Outer\$Inner#").size)
    }

    @Test
    @DisplayName("a type the compiler did not resolve is not attributed, as before")
    fun unresolvedTypeIsNotAttributed() {
        val doc = build(
            "package com.example\nclass Thing { Thing() { } }\nclass A { void run() { new Thing() } }",
            at = Phases.CONVERSION,
        )

        assertEquals(emptyList<SymbolOccurrence>(), doc.constructorCalls())
    }
}
