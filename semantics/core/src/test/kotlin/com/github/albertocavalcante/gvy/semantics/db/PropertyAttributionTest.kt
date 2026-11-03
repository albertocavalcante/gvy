package com.github.albertocavalcante.gvy.semantics.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Property and field references: credited to the declaring source class, and only when nothing else can answer. */
class PropertyAttributionTest : SymbolIdTestSupport() {

    @Test
    @DisplayName("a property of a class reference that java.lang.Class has is not the class's property")
    fun classObjectPropertyIsNotAttributed() {
        // Person.name is Class.getName(), "Person", not the instance property.
        val doc = build("class Person {\n    String name\n    static def run() { Person.name }\n}")
        assertEquals(
            emptyList<String>(),
            doc.occurrences.filter {
                it.role == OccurrenceRole.REFERENCE && it.symbol == "Person#name."
            }.map { it.range },
        )
    }

    @Test
    @DisplayName("an inherited property read through a subclass is credited to the class that declares it")
    fun inheritedPropertyIsCreditedToDeclaringClass() {
        val doc = build(
            """
            class Base { String name }
            class Sub extends Base {
                def run(Sub t) { [this.name, t.name] }
            }
            """,
        )
        val refs = doc.propertyRefs("name")
        assertEquals(listOf("Base#name.", "Base#name."), refs, "got: $refs")
    }

    @Test
    @DisplayName("a property read on a class that overrides getProperty is not attributed")
    fun getPropertyOverrideMakesPropertyReadsDynamic() {
        val doc = build(
            """
            class D {
                String name
                def getProperty(String n) { 'dyn' }
            }
            class U { def run(D d) { d.name } }
            """,
        )
        assertEquals(emptyList<String>(), doc.propertyRefs("name"))
    }

    @Test
    @DisplayName("this in a static method is the Class object, so a Class property is not the class's")
    fun thisInStaticMethodReadsClassProperties() {
        // The old code credited Q#simpleName. although Q declares no such property: it is Class.getSimpleName().
        val doc = build("class Q {\n    static def run() { this.simpleName }\n}")
        assertEquals(emptyList<String>(), doc.propertyRefs("simpleName"))
    }

    @Test
    @DisplayName("a property read on a Map is a key lookup, not a property")
    fun propertyOfMapIsAKeyLookup() {
        val doc = build("class M extends HashMap {\n    String name\n    def run(M m) { m.name }\n}")
        assertEquals(emptyList<String>(), doc.propertyRefs("name"))
    }

    @Test
    @DisplayName("a bare name inside a closure is not attributed to a field: the delegate may have it")
    fun bareNameInClosureIsNotAttributedToField() {
        val doc = build("class Cur {\n    String name\n    def run(Map cfg) { cfg.with { name } }\n}")
        assertEquals(
            emptyList<String>(),
            doc.occurrences.filter { it.role == OccurrenceRole.REFERENCE && it.symbol == "Cur#name." }.map { it.range },
        )
    }

    private fun SemanticDocument.propertyRefs(name: String) = occurrences
        .filter { it.role == OccurrenceRole.REFERENCE && it.symbol.endsWith("#$name.") }
        .map { it.symbol }
}
