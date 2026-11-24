// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SymbolTest {

    /** What formatting a shape must give and what parsing that id must read back. */
    private class Shape(
        val name: String,
        val symbol: Symbol,
        val id: String,
        val owner: String,
        val member: String,
        val descriptor: String?,
    )

    private val cls = Symbol.ofClass("com.example.MyClass")
    private val method = Symbol.ofMethod(cls, "run", "String,int")

    private val shapes = listOf(
        Shape("class", cls, "com/example/MyClass#", "com/example/MyClass", "", null),
        Shape(
            "nested class",
            Symbol.ofClass("com.example.Outer\$Inner"),
            "com/example/Outer\$Inner#",
            "com/example/Outer\$Inner",
            "",
            null,
        ),
        Shape("default package class", Symbol.ofClass("Foo"), "Foo#", "Foo", "", null),
        Shape(
            "field or property",
            Symbol.ofField(cls, "name"),
            "com/example/MyClass#name.",
            "com/example/MyClass",
            "name",
            null,
        ),
        Shape(
            "method without parameters",
            Symbol.ofMethod(cls, "run", ""),
            "com/example/MyClass#run().",
            "com/example/MyClass",
            "run",
            "",
        ),
        Shape("method", method, "com/example/MyClass#run(String,int).", "com/example/MyClass", "run", "String,int"),
        Shape(
            "constructor",
            Symbol.ofMethod(cls, "<init>", "int"),
            "com/example/MyClass#<init>(int).",
            "com/example/MyClass",
            "<init>",
            "int",
        ),
        Shape(
            "method of a script without a class",
            Symbol.ofMethod(null, "run", "int"),
            "run(int).",
            "run(int).",
            "run(int).",
            "int",
        ),
        // A parameter and a local read as the method they belong to; nothing reads their own name.
        Shape(
            "parameter",
            Symbol.ofParameter(method, "x"),
            "com/example/MyClass#run(String,int).(x)",
            "com/example/MyClass",
            "run",
            "String,int",
        ),
        Shape(
            "local",
            Symbol.ofLocal(method, "x", 3, 4),
            "com/example/MyClass#run(String,int).x@3:4",
            "com/example/MyClass",
            "run",
            "String,int",
        ),
        Shape(
            "initializer local",
            Symbol.ofLocal(Symbol.ofInitializer(cls), "x", 1, 2),
            "com/example/MyClass#{init}.x@1:2",
            "com/example/MyClass",
            "{init}",
            null,
        ),
        Shape(
            "import",
            Symbol.ofImport("com.example.MyClass"),
            "import#com/example/MyClass",
            "import",
            "com/example/MyClass",
            null,
        ),
        Shape(
            "static import",
            Symbol.ofStaticImport("com.example.MyClass", "max"),
            "import#com/example/MyClass.max",
            "import",
            "com/example/MyClass",
            null,
        ),
        Shape("star import", Symbol.ofImport("com.example."), "import#com/example/", "import", "com/example/", null),
    )

    @Test
    fun `formatting each shape gives its id`() {
        shapes.forEach { assertEquals(it.id, it.symbol.id, it.name) }
    }

    @Test
    fun `parsing each formatted shape gives back its parts`() {
        shapes.forEach {
            val parsed = Symbol(it.id)
            assertEquals(it.owner, parsed.owner, "${it.name} owner")
            assertEquals(it.member, parsed.member, "${it.name} member")
            assertEquals(it.descriptor, parsed.descriptor, "${it.name} descriptor")
        }
    }

    @Test
    fun `a class id names its dotted class`() {
        assertEquals("com.example.MyClass", cls.ownerClassName)
        assertEquals("com.example.Outer\$Inner", Symbol.ofClass("com/example/Outer\$Inner").ownerClassName)
        assertEquals("Foo", Symbol.ofClass("Foo").ownerClassName)
    }

    @Test
    fun `a class id given with slashes or dots is the same id`() {
        assertEquals(Symbol.ofClass("a.b.C"), Symbol.ofClass("a/b/C"))
    }

    @Test
    fun `owner is the whole id without a hash and member the whole id too`() {
        assertEquals("plain", Symbol("plain").owner)
        assertEquals("plain", Symbol("plain").member)
        assertEquals("", Symbol("").owner)
        assertEquals("", Symbol("").member)
    }

    @Test
    fun `member reads a backtick-quoted name and falls back when the quote is open`() {
        assertEquals("<init>", Symbol("a/B#`<init>`().").member)
        assertEquals("`open", Symbol("a/B#`open").member)
    }

    @Test
    fun `owner reads before the first hash and member after the last`() {
        assertEquals("a", Symbol("a#b#c.").owner)
        assertEquals("c", Symbol("a#b#c.").member)
    }

    @Test
    fun `a descriptor needs an open parenthesis before a close one`() {
        assertNull(Symbol("a#m).(").descriptor)
        assertNull(Symbol("a#m(").descriptor)
        assertNull(Symbol("a#m)").descriptor)
        assertEquals("x", Symbol("a#m(x)(y).").descriptor)
    }

    @Test
    fun `arity counts top-level parameters`() {
        val rows = mapOf(
            "com/example/MyClass#" to 0,
            "com/example/MyClass#myField." to 0,
            "com/example/MyClass#myMethod()." to 0,
            "com/example/MyClass#myMethod(String,int)." to 2,
            "com/example/MyClass#<init>(int)." to 1,
            "com/example/MyClass#myMethod(int).(x)" to 1,
            "com/example/MyClass#myMethod(int).x@3:4" to 1,
            "com/example/MyClass#{init}.x@1:2" to 0,
            "import#com/example/MyClass.max" to 0,
            "myMethod()." to 0,
            "com/example/MyClass#m(Map<String,String>,int)." to 2,
            "a#m(List<Map<String,Integer>>,String)." to 2,
            // Degenerate rows: the builder writes simple erased names and arrays as `String[]`, never these forms.
            "a#m(String...)." to 1,
            "" to 0,
            "a#m( )." to 1,
            "a#m(a,,b)." to 3,
            "a#m(a,)." to 2,
            "a#m(,)." to 2,
            "a#m(a, b)." to 2,
            "a#m(Map<String)." to 1,
            "a#m(a>,b)." to 2,
            "a#m(>)." to 1,
            "a#m(x)(y)." to 1,
            "a#m)(." to 0,
            "a#m(." to 0,
        )
        rows.forEach { (id, expected) -> assertEquals(expected, Symbol(id).arity, "for id '$id'") }
    }

    @Test
    fun `parameter types are the trimmed pieces as written`() {
        val rows = mapOf(
            "com/example/MyClass#myField." to emptyList(),
            "com/example/MyClass#myMethod()." to emptyList(),
            "com/example/MyClass#myMethod(String,int)." to listOf("String", "int"),
            "com/example/MyClass#<init>(int)." to listOf("int"),
            "com/example/MyClass#myMethod(int).(x)" to listOf("int"),
            "com/example/MyClass#myMethod(int).x@3:4" to listOf("int"),
            "com/example/MyClass#m(Map<String,String>,int)." to listOf("Map<String,String>", "int"),
            "a#m(java.lang.String)." to listOf("java.lang.String"),
            "a#m(String[])." to listOf("String[]"),
            // Degenerate rows: the builder writes simple erased names and arrays as `String[]`, never these forms.
            "a#m(String...)." to listOf("String..."),
            "a#m( )." to emptyList(),
            "a#m(a,,b)." to listOf("a", "", "b"),
            "a#m(a,)." to listOf("a"),
            "a#m(,)." to listOf(""),
            "a#m( a , b )." to listOf("a", "b"),
            "a#m(Map<String)." to listOf("Map<String"),
            "a#m(a>,b)." to listOf("a>", "b"),
            "a#m)(." to emptyList(),
            "" to emptyList(),
        )
        rows.forEach { (id, expected) -> assertEquals(expected, Symbol(id).parameterTypes, "for id '$id'") }
    }

    @Test
    fun `arity and parameter types disagree on blank and trailing-empty descriptors`() {
        // Kept from the parsers this type replaced: the count sees the untrimmed text, the list trims first.
        assertEquals(1, Symbol("a#m( ).").arity)
        assertEquals(emptyList<String>(), Symbol("a#m( ).").parameterTypes)
        assertEquals(2, Symbol("a#m(a,).").arity)
        assertEquals(listOf("a"), Symbol("a#m(a,).").parameterTypes)
    }

    @Test
    fun `formatters are byte-identical to the templates they replaced`() {
        val classNames = listOf("com.example.MyClass", "com/example/MyClass", "Foo", "a.b.Outer\$Inner", "")
        classNames.forEach { name ->
            assertEquals("${name.replace('.', '/')}#", Symbol.ofClass(name).id, "ofClass $name")
            assertEquals("$name#", Symbol.ofInternalName(name).id, "ofInternalName $name")
            listOf("field", "x1", "").forEach { member ->
                val owner = name.replace('.', '/')
                assertEquals(
                    "$owner#$member.",
                    Symbol.ofField(Symbol.ofClass(name), member).id,
                    "ofField $name $member",
                )
                assertEquals("$name#$member.", Symbol.ofField(Symbol.ofInternalName(name), member).id)
            }
        }
        assertEquals("a/B#m(String,int).", Symbol.ofMethod(Symbol.ofClass("a.B"), "m", "String,int").id)
        assertEquals("m().", Symbol.ofMethod(null, "m", "").id)
        assertEquals("a/B#m(int).(x)", Symbol.ofParameter(Symbol("a/B#m(int)."), "x").id)
        assertEquals("a/B#m(int).x@3:4", Symbol.ofLocal(Symbol("a/B#m(int)."), "x", 3, 4).id)
        assertEquals("a/B#{init}.", Symbol.ofInitializer(Symbol.ofClass("a.B")).id)
        assertEquals("import#a/b/C", Symbol.ofImport("a.b.C").id)
        assertEquals("import#a/b/C.m", Symbol.ofStaticImport("a.b.C", "m").id)
    }

    @Test
    fun `a method name containing a hash round-trips`() {
        val id = Symbol.ofMethod(Symbol.ofClass("a.B"), "fixes #12", "int")

        assertEquals("a/B#fixes \\#12(int).", id.id)
        assertEquals("a/B", id.owner)
        assertEquals("fixes #12", id.member)
        assertEquals("int", id.descriptor)
        assertEquals(1, id.arity)
    }

    @Test
    fun `escaped ids are written exactly`() {
        val owner = Symbol.ofClass("a.B")
        assertEquals("a/B#a\\.b().", Symbol.ofMethod(owner, "a.b", "").id)
        assertEquals("a/B#f\\(x\\)().", Symbol.ofMethod(owner, "f(x)", "").id)
        assertEquals("a/B#back\\\\slash(int).", Symbol.ofMethod(owner, "back\\slash", "int").id)
        assertEquals("a/B#a\\.b\\(c\\)().", Symbol.ofMethod(owner, "a.b(c)", "").id)
        assertEquals("a/B#\\`q\\`.", Symbol.ofField(owner, "`q`").id)
    }

    @Test
    fun `method names containing separators round-trip`() {
        val names = listOf(
            "fixes #12",
            "a.b",
            "f(x)",
            "open(",
            "close)",
            "back\\slash",
            "ends with backslash\\",
            "#.()\\",
            "`quoted`",
            "a#b#c",
            "<init>",
            "plain",
        )
        names.forEach { name ->
            val method = Symbol.ofMethod(Symbol.ofClass("a.B"), name, "String,int")
            val parsed = Symbol(method.id)
            assertEquals("a/B", parsed.owner, "owner of '$name'")
            assertEquals(name, parsed.member, "member of '$name'")
            assertEquals("String,int", parsed.descriptor, "descriptor of '$name'")
            assertEquals(2, parsed.arity, "arity of '$name'")
            assertEquals(listOf("String", "int"), parsed.parameterTypes, "parameter types of '$name'")
        }
    }

    @Test
    fun `members and parameters with separators read as their method`() {
        val method = Symbol.ofMethod(Symbol.ofClass("a.B"), "fixes #12.x(y)", "int")

        assertEquals("fixes #12.x(y)", Symbol.ofParameter(method, "p)q").member)
        assertEquals(listOf("int"), Symbol.ofParameter(method, "p)q").parameterTypes)
        assertEquals("fixes #12.x(y)", Symbol.ofLocal(method, "v#w", 1, 2).member)
    }

    @Test
    fun `a field name containing separators round-trips`() {
        val field = Symbol.ofField(Symbol.ofClass("a.B"), "odd#name.(x)")

        assertEquals("a/B", field.owner)
        assertEquals("odd#name.(x)", field.member)
    }

    @Test
    fun `different method names never share an id`() {
        val owner = Symbol.ofClass("a.B")
        val ids = listOf("fixes #12", "fixes \\#12", "fixes", "fixes.", "fixes(").map { Symbol.ofMethod(owner, it, "") }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `toString is the id`() {
        assertEquals("com/example/MyClass#", cls.toString())
    }
}
