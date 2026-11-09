package com.github.albertocavalcante.gvy.semantics.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The names a document uses that the index could not attribute, plus `*` for sites whose name is not
 * known statically. A workspace-wide rename is only safe when no file has an uncertain use of the name.
 */
class UnresolvedNamesTest : SymbolIdTestSupport() {

    private fun names(code: String) = build(code).unresolvedNames

    @Test
    @DisplayName("a call on an untyped receiver is recorded")
    fun untypedReceiver() {
        assertEquals(setOf("bar"), names("def x = foo(); x.bar()").minus("foo"))
    }

    @Test
    @DisplayName("an attributed call is not recorded")
    fun attributedCall() {
        val doc = build("class C { void run() {} }\nclass D { void go(C c) { c.run() } }")
        assertEquals(1, doc.calls("run").size)
        assertEquals(emptySet<String>(), doc.unresolvedNames)
    }

    @Test
    @DisplayName("an implicit-this call inside a with {} closure is recorded")
    fun withClosure() {
        val doc = build("class C { void helper() {}\n void run(Object o) { o.with { helper() } } }")
        assertEquals(setOf("helper"), doc.unresolvedNames.minus("with"))
    }

    @Test
    @DisplayName("a GString method name is a dynamic site")
    fun gstringMethod() {
        assertEquals(setOf("*"), names("class C { void run(Object o, String n) { o.\"\$n\"() } }"))
    }

    @Test
    @DisplayName("invokeMethod with a constant name records that name")
    fun invokeMethodConstant() {
        val found = names("class C { void run(Object o) { o.invokeMethod('baz', null) } }")
        assertEquals(true, "baz" in found)
        assertEquals(false, "*" in found)
    }

    @Test
    @DisplayName("invokeMethod with a non-constant name is a dynamic site")
    fun invokeMethodDynamic() {
        assertEquals(true, "*" in names("class C { void run(Object o, String n) { o.invokeMethod(n, null) } }"))
    }

    @Test
    @DisplayName("a class declaring methodMissing is a dynamic site")
    fun methodMissing() {
        assertEquals(true, "*" in names("class C { def methodMissing(String n, Object a) { null } }"))
    }

    @Test
    @DisplayName("a class inheriting methodMissing from a source supertype is a dynamic site")
    fun inheritedMethodMissing() {
        val found = names("class B { def methodMissing(String n, Object a) { null } }\nclass C extends B {}")
        assertEquals(true, "*" in found)
    }

    @Test
    @DisplayName("an ordinary class is not a dynamic site")
    fun ordinary() {
        assertEquals(emptySet<String>(), names("class C { String name; String get() { name } }"))
    }

    @Test
    @DisplayName("an unattributed property read on a Map is recorded")
    fun mapPropertyRead() {
        assertEquals(setOf("size"), names("class C { Object run(Map m) { m.size } }"))
    }

    @Test
    @DisplayName("an attributed field read is not recorded")
    fun attributedField() {
        val doc = build("class C { String name; String get(C c) { c.name } }")
        assertEquals(emptySet<String>(), doc.unresolvedNames)
    }

    @Test
    @DisplayName("a bare name that is neither a local nor a field is recorded")
    fun bareName() {
        assertEquals(true, "ghost" in names("ghost + 1"))
        assertEquals(emptySet<String>(), names("class C { String f; Object run(int p) { int l = p; f + l } }"))
    }

    @Test
    @DisplayName("an attribute read and a spread read are recorded")
    fun attributeAndSpread() {
        val found = names("class C { String f; Object run(C c, List<C> cs) { [c.@f, cs*.f] } }")
        assertEquals(true, "f" in found)
    }

    @Test
    @DisplayName("a GString property name and a non-numeric index on an untyped receiver are dynamic sites")
    fun dynamicProperty() {
        assertEquals(setOf("*"), names("class C { Object run(Object o, String n) { o.\"\$n\" } }"))
        assertEquals(true, "*" in names("class C { Object run(Object o, String n) { o[n] } }"))
        assertEquals(emptySet<String>(), names("class C { Object run(Map m, List l, String n) { [m[n], l[0]] } }"))
    }

    @Test
    @DisplayName("a method pointer to an unattributed name is recorded")
    fun methodPointer() {
        assertEquals(true, "go" in names("class C { Object run(Object o) { o.&go } }"))
    }
}
