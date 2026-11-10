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
        assertEquals(setOf("size", "getSize", "isSize"), names("class C { Object run(Map m) { m.size } }"))
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
        assertEquals(emptySet<String>(), names("class C { String f; Object run(int p) { int l = p; [f, l] } }"))
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

    // Groovy rewrites static-import and static-context implicit-this calls into StaticMethodCallExpression.

    @Test
    @DisplayName("a call through a static import is recorded")
    fun staticImportCall() {
        val found = names("import static C.helper\nclass C { static helper() {} }\nclass D { def m() { helper() } }")
        assertEquals(true, "helper" in found)
    }

    @Test
    @DisplayName("an implicit-this call from a static method is recorded")
    fun staticContextCall() {
        assertEquals(true, "b" in names("class C { static a() { b() }; static b() {} }"))
    }

    @Test
    @DisplayName("a call to a statically imported JDK method is recorded")
    fun staticJdkImportCall() {
        val found = names("import static java.util.Collections.emptyList\nclass D { def m() { emptyList() } }")
        assertEquals(true, "emptyList" in found)
    }

    // Operators dispatch to methods the source never names.

    private fun operatorNames(expression: String, params: String = "Object a, Object b") =
        names("class C { def run($params) { $expression } }")

    @Test
    @DisplayName("binary operators on untyped operands record the method they dispatch to")
    fun binaryOperators() {
        val expected = mapOf(
            "a + b" to "plus", "a - b" to "minus", "a * b" to "multiply", "a / b" to "div", "a % b" to "mod",
            "a ** b" to "power", "a << b" to "leftShift", "a >> b" to "rightShift", "a | b" to "or",
            "a & b" to "and", "a ^ b" to "xor", "a <=> b" to "compareTo", "a < b" to "compareTo",
            "a >= b" to "compareTo", "a == b" to "equals", "a != b" to "equals", "a in b" to "isCase",
            "a as String" to "asType", "-a" to "negative", "a++" to "next", "--a" to "previous",
            "a[0]" to "getAt", "a[0] = b" to "putAt", "a += b" to "plus",
        )
        expected.forEach { (code, method) ->
            assertEquals(true, method in operatorNames(code), "`$code` should record $method")
        }
    }

    @Test
    @DisplayName("numeric operands do not dispatch to anything user-defined")
    fun numericOperators() {
        val found = operatorNames("[a + b, a < b, a++, -a, a == 1]", "int a, int b")
        assertEquals(emptySet<String>(), found)
    }

    @Test
    @DisplayName("case and for-in record isCase and iterator")
    fun statements() {
        assertEquals(true, "isCase" in operatorNames("switch (a) { case b: return 1 }"))
        assertEquals(true, "iterator" in operatorNames("for (x in a) { x }"))
        assertEquals(emptySet<String>(), operatorNames("for (x in [1, 2]) { x }", "int a"))
    }

    // A getter or setter is how the source names a property read or write.

    @Test
    @DisplayName("a property read records the getters it may call, a write the setter")
    fun accessorNames() {
        val decl = "String getName() { 'x' }\nvoid setName(String s) {}\n"
        assertEquals(
            setOf("name", "getName", "isName"),
            names("class C { ${decl}Object run(C c) { c.name } }"),
        )
        assertEquals(
            setOf("name", "setName"),
            names("class C { ${decl}void run(C c) { c.name = 'y' } }"),
        )
        assertEquals(
            setOf("name", "getName", "isName", "setName"),
            names("class C { ${decl}void run(C c) { c.name += 'y' } }").minus("plus"),
        )
    }

    // Annotation members are named by the source and attributed to nothing.

    @Test
    @DisplayName("annotation member names are recorded wherever the annotation sits")
    fun annotationMembers() {
        assertEquals(true, "v" in names("@interface A { int v() }\n@A(v = 1) class C {}"))
        assertEquals(true, "v" in names("@interface A { int v() }\nclass C { @A(v = 1) void m() {} }"))
        assertEquals(true, "v" in names("@interface A { int v() }\nclass C { @A(v = 1) String f }"))
        assertEquals(true, "v" in names("@interface A { int v() }\nclass C { void m(@A(v = 1) String p) {} }"))
        assertEquals(true, "v" in names("@interface A { int v() }\n@A(v = 1) def f() {}\nf()"))
    }

    // One definition of "dispatches by itself": GroovyDispatch.

    @Test
    @DisplayName("@Delegate and a setProperty override are dynamic sites; a plain script is not")
    fun dispatchHooks() {
        assertEquals(true, "*" in names("class W { @Delegate List<String> inner }"))
        assertEquals(true, "*" in names("class S { void setProperty(String n, Object v) {} }"))
        assertEquals(true, "*" !in names("def x = 1\nprintln x"))
    }

    // Cases found by independent review of the first version.

    @Test
    @DisplayName("a bare call to a static method is recorded from a static and from an instance context")
    fun staticBareCalls() {
        assertEquals(true, "target" in names("class C { static void target() {}; void run() { target() } }"))
        assertEquals(true, "target" in names("class C { static void target() {}; static void run() { target() } }"))
    }

    @Test
    @DisplayName("a static import records the member, and the original name behind an alias")
    fun staticImports() {
        val aliased = "import static C.target as tgt\nclass C { static target() {} }\nclass D { def m() { tgt() } }"
        assertEquals(true, "target" in names(aliased))
        val field = "import static C.FIELD\nclass C { static final String FIELD = 'x' }\nclass D { def m() { FIELD } }"
        assertEquals(true, "FIELD" in names(field))
    }

    @Test
    @DisplayName("named arguments record the property keys and their setters, on a constructor and on a call")
    fun namedArguments() {
        val found = names("class P { String target }\nclass Q { def m() { new P(target: 'x') } }")
        assertEquals(true, "target" in found && "setTarget" in found)
        assertEquals(true, "target" in names("def x = foo(target: 1)"))
    }

    @Test
    @DisplayName("instance initializer blocks and parameter default values are visited")
    fun initializersAndDefaults() {
        assertEquals(true, "hidden" in names("class C { { hidden() } }"))
        assertEquals(true, "hidden" in names("class C { def m(o = hidden()) {} }"))
    }

    @Test
    @DisplayName("a trait is not a dynamic site, and its body's uncertain uses are recorded")
    fun traits() {
        val alone = names("trait T { def run() { this.target } }")
        assertEquals(false, "*" in alone)
        assertEquals(true, "target" in alone)
        val implemented = "trait T { def target() {} }\nclass C implements T { def run() { target() } }"
        assertEquals(false, "*" in names(implemented))
    }

    @Test
    @DisplayName("every kind of use inside a trait body is recorded")
    fun traitBody() {
        val body = names("trait T { def go(o) { o.zork(); unknownName; this.bar(); o.qux = 1; o + o } }")
        assertEquals(true, body.containsAll(setOf("zork", "unknownName", "bar", "qux", "setQux", "plus")))
        assertEquals(true, "target" in names("trait T { static def go(o) { o.target() } }"))
        assertEquals(true, "target" in names("trait T { def go(o) { new P(target: 1) } }\nclass P { String target }"))
        assertEquals(emptySet<String>(), body.filter { '$' in it }.toSet())
    }

    @Test
    @DisplayName("an anonymous class body, and a closure inside it, are recorded without symbols or occurrences")
    fun anonymousClassBody() {
        val code = "class C { def run() { new Runnable() { void run() { Object x = null; x.zork() } } } }"
        assertEquals(true, "zork" in names(code))
        val closure = "class C { def run() { new Runnable() { void run() { def o = null; [1].each { o.zork2() } } } } }"
        assertEquals(true, "zork2" in names(closure))
        val doc = build(code)
        assertEquals(emptyList<SymbolInfo>(), doc.symbols.filter { it.name == "x" })
    }

    @Test
    @DisplayName("an accessor-shaped call records the property it may stand for; a static star import's field too")
    fun accessorCalls() {
        val found = names("class C { def run(Object o) { o.getTarget(); o.setUrlName(1); o.getURL() } }")
        assertEquals(true, found.containsAll(setOf("target", "urlName", "URL", "uRL")))
        val star = "import static C.*\nclass C { static String target }\nclass D { def m() { target } }"
        assertEquals(true, "target" in names(star))
    }

    // Parameter defaults and instance initializers are attributed like the same expression in a method body.

    @Test
    @DisplayName("a call in a parameter default gets the id and range it would get in a body")
    fun callInDefault() {
        val doc = build("class C {\n    def foo() {}\n    def m(x = foo()) {}\n    def n() { foo() }\n}")
        val calls = doc.occurrences.filter { it.role == OccurrenceRole.CALL && it.symbol == "C#foo()." }
        assertEquals(setOf(Range(2, 14, 2, 17), Range(3, 14, 3, 17)), calls.map { it.range }.toSet())
    }

    @Test
    @DisplayName("an earlier parameter used in a default is the parameter's id")
    fun parameterInDefault() {
        val doc = build("class C {\n    def m(int x, y = x) {}\n}")
        val refs = doc.occurrences.filter { it.role == OccurrenceRole.REFERENCE }
        assertEquals(listOf("C#m(int,Object).(x)" to Range(1, 21, 1, 22)), refs.map { it.symbol to it.range })
    }

    @Test
    @DisplayName("a call and a field read in an instance initializer are attributed")
    fun initializerOccurrences() {
        val doc = build("class C {\n    int f\n    def foo() {}\n    {\n        foo()\n        f\n    }\n}")
        val init = doc.occurrences.filter { it.range.startLine >= 4 }.map { Triple(it.symbol, it.role, it.range) }
        assertEquals(
            listOf(
                Triple("C#foo().", OccurrenceRole.CALL, Range(4, 8, 4, 11)),
                Triple("C#f.", OccurrenceRole.REFERENCE, Range(5, 8, 5, 9)),
            ),
            init,
        )
    }

    @Test
    @DisplayName("compiler-generated code leaves no names behind")
    fun generated() {
        assertEquals(emptySet<String>(), names("enum E { A, B }"))
        val traits = names("trait T { def x() { 1 } }\nclass C implements T {}")
        assertEquals(emptySet<String>(), traits.filter { '$' in it }.toSet())
    }

    @Test
    @DisplayName("the recorded set cannot be modified")
    fun unmodifiable() {
        @Suppress("UNCHECKED_CAST")
        val names = names("class C {}") as MutableSet<String>
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException::class.java) { names.add("x") }
    }

    @Test
    @DisplayName("respondsTo and hasProperty with a constant name record that name")
    fun reflection() {
        assertEquals(true, "target" in names("class C { def run(Object o) { o.respondsTo('target') } }"))
        assertEquals(true, "target" in names("class C { def run(Object o) { o.hasProperty('target') } }"))
    }

    @Test
    @DisplayName("a resolved use in a class that records nothing is still an unattributed use")
    fun resolvedInUnrecordedClass() {
        val trait = names("class K { def known() {} }\ntrait T { def bar() {}; def go(K k) { bar(); k.known() } }")
        assertEquals(true, trait.containsAll(setOf("bar", "known")))
        val enumBody = names("class K { def known() {} }\nenum E { A { def x(K k) { k.known() } }; def y() {} }")
        assertEquals(true, "known" in enumBody)
        val both = names("trait T { def target() {}; def run() { target(); this.target() } }")
        assertEquals(true, "target" in both)
        val props =
            names(
                "class P { String target }\ntrait T { String target; def run(P p) { this.target; target; p.target } }",
            )
        assertEquals(true, "target" in props)
        val anonymous = names("class C { def run() { new Object() { def target; def r() { target } } } }")
        assertEquals(true, "target" in anonymous)
    }

    @Test
    @DisplayName("a trait field read, write and compound assignment record the field and its accessors")
    fun traitFields() {
        assertEquals(true, names("trait T { int cnt = 0; def get1() { cnt } }").containsAll(setOf("cnt", "getCnt")))
        assertEquals(true, names("trait T { int cnt = 0; def set1() { cnt = 1 } }").containsAll(setOf("cnt", "setCnt")))
        val compound = names("trait T { int cnt = 0; def go() { cnt += 1 } }")
        assertEquals(true, compound.containsAll(setOf("cnt", "getCnt", "setCnt")))
        assertEquals(emptySet<String>(), compound.filter { '$' in it }.toSet())
        assertEquals(true, "a__b" in names("trait T { int a__b = 0; def go() { a__b } }"))
        assertEquals(true, "ação" in names("trait T { int ação = 0; def go() { ação } }"))
    }

    @Test
    @DisplayName("an inner class is not a dynamic site, but a hook the source wrote in one is")
    fun innerClasses() {
        assertEquals(false, "*" in names("class C { class I { def m() { 1 } } }"))
        assertEquals(false, "*" in names("class C { def run() { new Object() { def m() { 1 } } } }"))
        assertEquals(true, "*" in names("class C { class I { def methodMissing(String n, Object a) { null } } }"))
    }

    @Test
    @DisplayName("inside a trait only the transform's own names are dropped, not a user's `$` name")
    fun dollarNames() {
        val found = names("trait T { def go(o) { o.tar\$get() } }")
        assertEquals(true, "tar\$get" in found)
    }
}
