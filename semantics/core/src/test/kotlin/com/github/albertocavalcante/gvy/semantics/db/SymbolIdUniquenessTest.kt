package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration

/**
 * Symbol ids must be unique per declaration, otherwise a workspace-wide rename or find-references
 * would touch unrelated symbols. These tests pin the id scheme down:
 *
 * - fields:      `Cls#x.`
 * - methods:     `Cls#m(String,int).` (erased parameter types, simple names)
 * - parameters:  `Cls#m(String,int).(p)`
 * - locals:      `Cls#m(String,int).x@line:col` (0-indexed declaration position)
 */
open class SymbolIdUniquenessTest {

    private val uri = URI.create("file:///Test.groovy")

    /** The compile phase the AST reaches before indexing; production uses CANONICALIZATION. */
    protected open val phase: Int = Phases.CONVERSION

    /**
     * Builds the document for [code]. Above CONVERSION the source is compiled to [phase] and a failure
     * fails the test, unless the fixture names a class that is not in the source ([knownError]); such a
     * fixture must in fact fail, so the flag cannot go stale.
     */
    private fun build(code: String, knownError: Boolean = false): SemanticDocument {
        if (phase == Phases.CONVERSION) {
            val sourceUnit = SourceUnit("Test.groovy", code.trimIndent(), CompilerConfiguration(), null, null)
            sourceUnit.parse()
            sourceUnit.completePhase()
            sourceUnit.convert()
            return SemanticDocumentBuilder(sourceUnit.ast, uri).build()
        }
        val unit = CompilationUnit(CompilerConfiguration())
        unit.addSource("Test.groovy", code.trimIndent())
        val failure = runCatching { unit.compile(phase) }.exceptionOrNull()
        if (knownError) {
            assertTrue(failure != null, "fixture is marked as a known compile error but compiled cleanly")
        } else {
            assertEquals(null, failure, "fixture must compile to phase $phase")
            assertTrue(unit.phase >= phase, "compile stopped at phase ${unit.phase}, before $phase")
        }
        // Like production, index whatever AST exists even when resolution reports errors (e.g. other files).
        return SemanticDocumentBuilder(unit.ast.modules.single(), uri).build()
    }

    private fun SemanticDocument.calls(name: String) = occurrences.filter {
        it.role == OccurrenceRole.CALL && it.symbol.contains("#$name(")
    }

    /** The id of the one method definition `Cls#name(params).`, whatever the package. */
    private fun SemanticDocument.def(cls: String, signature: String): String = symbols.single {
        it.kind == SymbolKind.METHOD && it.symbol.substringAfterLast('/') == "$cls#$signature."
    }.symbol

    @Test
    @DisplayName("locals with the same name in different methods get different ids, distinct from the field")
    fun localsAreUniquePerDeclaration() {
        val doc = build(
            """
            class A {
                int x
                void m1() { int x = 1; println(x) }
                void m2() { int x = 2; println(x) }
            }
            """,
        )

        val locals = doc.symbols.filter { it.kind == SymbolKind.VARIABLE && it.name == "x" }
        val field = doc.symbols.single { it.kind == SymbolKind.FIELD && it.name == "x" }

        assertEquals(2, locals.size, "each local declaration is a symbol")
        assertEquals(2, locals.map { it.symbol }.toSet().size, "the two locals have different ids")
        assertFalse(field.symbol in locals.map { it.symbol }, "a local never shares the field's id")
        locals.forEach { local ->
            val refs = doc.occurrences.filter { it.symbol == local.symbol && it.role == OccurrenceRole.REFERENCE }
            assertEquals(1, refs.size, "the use of ${local.symbol} points at its own declaration")
        }
        assertTrue(
            doc.occurrences.none { it.symbol == field.symbol && it.role == OccurrenceRole.REFERENCE },
            "shadowed uses must not be attributed to the field",
        )
    }

    @Test
    @DisplayName("same-named locals in nested closures and blocks get separate ids")
    fun shadowedLocalsInNestedScopes() {
        val doc = build(
            """
            class A {
                void m() {
                    def x = 1
                    def c = { def x = 2; x }
                    println(x)
                }
            }
            """,
            knownError = true, // Groovy rejects the shadowing at SEMANTIC_ANALYSIS; the AST is still indexed
        )
        val locals = doc.symbols.filter { it.kind == SymbolKind.VARIABLE && it.name == "x" }
        assertEquals(2, locals.map { it.symbol }.toSet().size)
        val uses = doc.occurrences.filter {
            it.role == OccurrenceRole.REFERENCE &&
                it.symbol in locals.map { l -> l.symbol }
        }
        assertEquals(2, uses.size)
        assertEquals(2, uses.map { it.symbol }.toSet().size, "inner use binds the inner x, outer use the outer x")
    }

    @Test
    @DisplayName("parameters with the same name in different methods get different ids, distinct from the field")
    fun parametersAreUniquePerMethod() {
        val doc = build(
            """
            class A {
                int p
                void m1(int p) { println(p) }
                void m2(String p) { println(p) }
            }
            """,
        )

        val params = doc.symbols.filter { it.kind == SymbolKind.PARAMETER && it.name == "p" }
        val field = doc.symbols.single { it.kind == SymbolKind.FIELD && it.name == "p" }

        assertEquals(2, params.map { it.symbol }.toSet().size)
        assertFalse(field.symbol in params.map { it.symbol })
        params.forEach { param ->
            val refs = doc.occurrences.filter { it.symbol == param.symbol && it.role == OccurrenceRole.REFERENCE }
            assertEquals(1, refs.size, "the use of ${param.symbol} resolves to the parameter definition")
        }
        assertTrue(doc.occurrences.none { it.symbol == field.symbol && it.role == OccurrenceRole.REFERENCE })
    }

    @Test
    @DisplayName("a reference to a real field still resolves to the field id")
    fun fieldReferencesKeepFieldId() {
        val doc = build(
            """
            class A {
                int x
                int get() { return x }
            }
            """,
        )
        val field = doc.symbols.single { it.kind == SymbolKind.FIELD }
        assertEquals(1, doc.occurrences.count { it.symbol == field.symbol && it.role == OccurrenceRole.REFERENCE })
    }

    @Test
    @DisplayName("overloads get different ids that carry the erased parameter types")
    fun overloadsHaveDistinctIds() {
        val doc = build(
            """
            class A {
                void add(int a) {}
                void add(String a) {}
                void add(List<String> a, String[] b) {}
            }
            """,
        )
        val ids = doc.symbols.filter { it.kind == SymbolKind.METHOD }.map { it.symbol }
        assertEquals(3, ids.toSet().size)
        assertTrue("A#add(int)." in ids, ids.toString())
        assertTrue("A#add(String)." in ids, ids.toString())
        assertTrue("A#add(List,String[])." in ids, ids.toString())
    }

    @Test
    @DisplayName("a call on an untyped receiver is not attributed to the enclosing class")
    fun untypedReceiverIsUnresolved() {
        val doc = build(
            """
            class A {
                void foo() {}
                void viaParam(def o) { o.foo() }
                void viaLocal() { def o = something(); o.foo() }
                void viaChain(A a) { a.other.foo() }
                void viaProperty(def o) { println(o.foo) }
            }
            """,
        )
        assertTrue(doc.calls("foo").isEmpty(), "got: ${doc.calls("foo")}")
        assertTrue(
            doc.occurrences.none { it.symbol == "A#foo." },
            "property access on an untyped receiver must not become A#foo.",
        )
    }

    @Test
    @DisplayName("receivers with a declared or inferred type, and this, still resolve")
    fun typedReceiversStillResolve() {
        val doc = build(
            """
            class A {
                A peer
                void foo() {}
                void viaThis() { this.foo(); foo() }
                void viaDeclared(A a) { a.foo() }
                void viaInferred() { def a = new A(); a.foo() }
                void viaField() { peer.foo() }
            }
            """,
        )
        assertEquals(5, doc.calls("foo").size)
        assertTrue(doc.calls("foo").all { it.symbol == "A#foo()." })
    }

    @Test
    @DisplayName("building identical content twice yields identical ids and occurrences")
    fun idsAreStableAcrossBuilds() {
        val code =
            """
            class A {
                int x
                void m(int p, String q) {
                    int x = 1
                    def c = { y -> y + x + p }
                    for (int i = 0; i < 2; i++) { println(i) }
                    try { c(1) } catch (Exception e) { println(e) }
                }
                void m(String s) {}
            }
            """
        val first = build(code)
        val second = build(code)
        assertEquals(first.symbols, second.symbols)
        assertEquals(first.occurrences, second.occurrences)
        assertNotEquals(0, first.symbols.size)
    }

    @Test
    @DisplayName("loop variables and catch parameters are locals with their own ids")
    fun loopAndCatchVariablesAreLocal() {
        val doc = build(
            """
            class A {
                int i
                void m() {
                    for (int i = 0; i < 2; i++) { println(i) }
                    try { } catch (Exception e) { println(e) }
                }
            }
            """,
        )
        val field = doc.symbols.single { it.kind == SymbolKind.FIELD }
        assertTrue(doc.occurrences.none { it.symbol == field.symbol && it.role == OccurrenceRole.REFERENCE })
        assertTrue(doc.symbols.any { it.kind == SymbolKind.VARIABLE && it.name == "i" })
    }

    @Test
    @DisplayName("a unique method in the superclass is credited to the declaring class from a subclass call")
    fun uniqueInheritedMethodResolvesToDeclaringClass() {
        val doc = build(
            """
            class Base { void foo() {} }
            class A extends Base {
                void run() { foo(); this.foo() }
            }
            class C {
                void use(A a) { a.foo(); new A().foo() }
            }
            """,
        )
        assertEquals(List(4) { doc.def("Base", "foo()") }, doc.calls("foo").map { it.symbol })
    }

    @Test
    @DisplayName("packaged classes resolve inherited methods to the packaged declaring id")
    fun packagedInheritedMethod() {
        val doc = build(
            """
            package p
            class Base { void foo(int x) {} }
            class A extends Base {
                void run() { foo(1) }
            }
            """,
        )
        val definition = doc.def("Base", "foo(int)")
        assertTrue(definition.startsWith("p/"), definition)
        assertEquals(listOf(definition), doc.calls("foo").map { it.symbol })
    }

    @Test
    @DisplayName("an override resolves to the nearest declaration")
    fun overrideResolvesToNearest() {
        val doc = build(
            """
            class Base { void foo() {} }
            class A extends Base {
                void foo() {}
                void run() { foo() }
            }
            """,
        )
        assertEquals(listOf(doc.def("A", "foo()")), doc.calls("foo").map { it.symbol })
    }

    @Test
    @DisplayName("a script's call to its own method resolves: its base class Script is a compiled, known class")
    fun scriptMethodCallResolves() {
        val doc = build(
            """
            def deploy(String env) { println(env) }
            deploy("prod")
            """,
        )
        if (phase == Phases.CONVERSION) return // the Script supertype is only a name until resolution
        assertEquals(listOf(doc.def("Test", "deploy(String)")), doc.calls("deploy").map { it.symbol })
    }

    @Test
    @DisplayName("an inherited classpath overload makes a same-arity local method ambiguous")
    fun classpathOverloadMakesLocalAmbiguous() {
        val doc = build(
            """
            class Bag extends ArrayList<Object> {
                boolean add(String s) { super.add(s) }
                void fill(def x) { add(x) }
            }
            """,
        )
        assertTrue(doc.calls("add").none { it.symbol == doc.def("Bag", "add(String)") }, "got: ${doc.calls("add")}")
    }

    @Test
    @DisplayName("same-name same-arity overloads leave the call unresolved, whatever the argument types")
    fun sameArityOverloadsAreUnresolved() {
        val doc = build(
            """
            class A {
                void foo(int x) {}
                void foo(String s) {}
                void run(def y) { foo(1); foo("s"); foo(y) }
            }
            """,
        )
        assertTrue(doc.calls("foo").isEmpty(), "got: ${doc.calls("foo")}")
    }

    @Test
    @DisplayName("an overload set that differs in arity is resolved by arity")
    fun arityPicksTheOverload() {
        val doc = build(
            """
            class A {
                void foo(int x) {}
                void foo(int x, int y) {}
                void run(def y) { foo(y); foo(1, 2); foo(1, 2, 3) }
            }
            """,
        )
        assertEquals(listOf(doc.def("A", "foo(int)"), doc.def("A", "foo(int,int)")), doc.calls("foo").map { it.symbol })
    }

    @Test
    @DisplayName("a trait method and a superclass method with the same name and arity leave the call unresolved")
    fun traitAndSuperclassCollide() {
        val doc = build(
            """
            trait T { void foo(String s) {} }
            class Base { void foo(int x) {} }
            class C extends Base implements T {
                void run() { foo(1) }
            }
            """,
        )
        assertTrue(doc.calls("foo").isEmpty(), "got: ${doc.calls("foo")}")
    }

    @Test
    @DisplayName("a trait method with the same signature as a superclass method is still a second candidate")
    fun traitDefaultWithSameSignatureIsUnresolved() {
        val doc = build(
            """
            trait T { void foo() {} }
            class Base { void foo() {} }
            class C extends Base implements T {
                void run() { foo() }
            }
            """,
        )
        assertTrue(doc.calls("foo").isEmpty(), "got: ${doc.calls("foo")}")
    }

    @Test
    @DisplayName("an interface method implemented by the class resolves to the class method")
    fun abstractInterfaceMethodImplementedByClass() {
        val doc = build(
            """
            interface I { void foo() }
            class A implements I {
                void foo() {}
                void run() { foo() }
            }
            class B {
                void use(I i) { i.foo() }
            }
            """,
        )
        assertEquals(listOf(doc.def("A", "foo()"), doc.def("I", "foo()")), doc.calls("foo").map { it.symbol })
    }

    @Test
    @DisplayName("a supertype outside the compilation makes inheritable calls unresolved")
    fun externalSupertypeLeavesCallsUnresolved() {
        val doc = build(
            """
            class A extends Missing implements AlsoMissing {
                void foo() {}
                void run() { foo(); this.foo() }
            }
            class B {
                void use(A a) { a.foo() }
            }
            """,
            knownError = true,
        )
        assertTrue(doc.calls("foo").isEmpty(), "got: ${doc.calls("foo")}")
    }

    @Test
    @DisplayName("in a workspace unit the hierarchy is visible across files, so inherited calls resolve to the other file")
    fun workspaceUnitSeesSupertypesInOtherFiles() {
        val unit = CompilationUnit(CompilerConfiguration())
        unit.addSource("Base.groovy", "package p\nclass Base { void foo() {} }")
        unit.addSource("A.groovy", "package p\nclass A extends Base { void run() { foo() } }")
        unit.addSource("B.groovy", "package p\nclass B { void run(Base b) { b.foo(); new A().foo() } }")
        unit.addSource("C.groovy", "package p\nclass C extends Base { void foo(int x) {}; void run() { foo(1) } }")
        unit.compile(Phases.SEMANTIC_ANALYSIS)
        val docs = unit.ast.modules.associate { it.description to SemanticDocumentBuilder(it, uri).build() }
        val definition = docs.getValue("Base.groovy").def("Base", "foo()")
        assertTrue(definition.startsWith("p/"), definition)
        assertEquals(listOf(definition), docs.getValue("A.groovy").calls("foo").map { it.symbol })
        assertEquals(List(2) { definition }, docs.getValue("B.groovy").calls("foo").map { it.symbol })
        // Arity still disambiguates across files: C.foo(int) and Base.foo() are two methods of C's hierarchy.
        assertEquals(
            listOf(docs.getValue("C.groovy").def("C", "foo(int)")),
            docs.getValue("C.groovy").calls("foo").map { it.symbol },
        )
    }

    @Test
    @DisplayName("a call on a class from another file is not attributed")
    fun externalReceiverIsUnresolved() {
        val doc = build(
            """
            class Main {
                void run() {
                    Calculator calc = new Calculator()
                    calc.add(5, 3)
                    calc.reset()
                }
            }
            """,
            knownError = true,
        )
        assertTrue(doc.calls("add").isEmpty() && doc.calls("reset").isEmpty(), "got: ${doc.occurrences}")
    }

    @Test
    @DisplayName("default parameters count as a min..max arity range")
    fun defaultParameters() {
        val doc = build(
            """
            class A {
                void m(int a, int b = 2) {}
                void run() { m(1); m(1, 2); m(); m(1, 2, 3) }
            }
            """,
        )
        assertEquals(List(2) { doc.def("A", "m(int,int)") }, doc.calls("m").map { it.symbol })
    }

    @Test
    @DisplayName("a varargs last parameter takes any count of at least params minus one")
    fun varargs() {
        val doc = build(
            """
            class A {
                void v(String... xs) {}
                void w(int a, String... rest) {}
                void run() { v(); v("a"); v("a", "b"); w(); w(1); w(1, "a", "b") }
            }
            """,
        )
        assertEquals(List(3) { doc.def("A", "v(String[])") }, doc.calls("v").map { it.symbol })
        assertEquals(List(2) { doc.def("A", "w(int,String[])") }, doc.calls("w").map { it.symbol })
    }

    @Test
    @DisplayName("a class that extends itself does not hang the resolver")
    fun cyclicInheritanceTerminates() {
        val doc = assertTimeoutPreemptively<SemanticDocument>(Duration.ofSeconds(20)) {
            build(
                """
                class A extends A {
                    void foo() {}
                    void run() { foo() }
                }
                """,
                knownError = true,
            )
        }
        assertTrue(doc.calls("foo").size <= 1)
    }

    @Test
    @DisplayName("Groovy argument coercions do not stop a unique candidate from resolving")
    fun coercionsResolveUniqueCandidates() {
        val doc = build(
            """
            class A {
                void scale(double f) {}
                void pay(BigDecimal amount) {}
                void items(ArrayList<String> xs) {}
                void table(LinkedHashMap<String, String> m) {}
                void letter(char c) {}
                void run() {
                    scale(1.5)
                    pay(10)
                    items([ 'a' ])
                    table([ a: 'b' ])
                    letter('a')
                }
            }
            """,
        )
        val expected = listOf(
            doc.def("A", "scale(double)"),
            doc.def("A", "pay(BigDecimal)"),
            doc.def("A", "items(ArrayList)"),
            doc.def("A", "table(LinkedHashMap)"),
            doc.def("A", "letter(char)"),
        )
        val called = doc.occurrences.filter { it.role == OccurrenceRole.CALL }.map { it.symbol }
        assertEquals(expected, called)
    }

    @Test
    @DisplayName("a spread argument has no known arity, so the call is unresolved")
    fun spreadArgumentIsUnresolved() {
        val doc = build(
            """
            class A {
                void foo(int a, int b) {}
                void run(List<Integer> xs) { foo(*xs) }
            }
            """,
        )
        assertTrue(doc.calls("foo").isEmpty())
    }

    @Test
    @DisplayName("an overload of Object's methods is not credited to the local one")
    fun objectMethodsAreCandidates() {
        val doc = build(
            """
            class A {
                boolean equals(A other) { true }
                void run(A a) { equals(a) }
            }
            """,
        )
        assertTrue(doc.calls("equals").isEmpty(), "got: ${doc.calls("equals")}")
    }

    @Test
    @DisplayName("super.foo() resolves into the superclass hierarchy only")
    fun superCallResolvesToSuperclass() {
        val doc = build(
            """
            class Base { void foo() {} }
            class A extends Base {
                void foo() { super.foo() }
            }
            """,
        )
        assertEquals(listOf(doc.def("Base", "foo()")), doc.calls("foo").map { it.symbol })
    }

    @Test
    @DisplayName("an implicit-this call inside a closure is unresolved: the closure's delegate may receive it")
    fun implicitThisInClosureIsUnresolved() {
        val doc = build(
            """
            class R {
                void append(String s) {}
                void m(StringBuilder sb) { sb.with { append('x') } }
            }
            """,
        )
        assertTrue(doc.calls("append").isEmpty(), "with {} sends append to the StringBuilder: ${doc.calls("append")}")
    }

    @Test
    @DisplayName("an explicit this call inside a closure still resolves: this is always the enclosing class")
    fun explicitThisInClosureResolves() {
        val doc = build(
            """
            class A {
                void foo(int x) {}
                void run() { def c = { this.foo(1) }; c() }
            }
            """,
        )
        assertEquals(listOf(doc.def("A", "foo(int)")), doc.calls("foo").map { it.symbol })
    }

    @Test
    @DisplayName("a name Groovy adds to every object (GDK) is unresolved: find { } calls the GDK find(Closure)")
    fun gdkNameIsUnresolved() {
        val doc = build(
            """
            class Repo {
                def find(String id) { id }
                void run() { this.find { it } }
            }
            """,
        )
        assertTrue(doc.calls("find").isEmpty(), "got: ${doc.calls("find")}")
    }

    @Test
    @DisplayName("a java.lang.Class method called on a class reference is not credited to the class")
    fun classMethodOnClassReferenceIsUnresolved() {
        val doc = build(
            """
            class Person {
                static String getName(int x) { "p" }
                void run() { Person.getName() }
            }
            """,
        )
        assertTrue(doc.calls("getName").isEmpty(), "got: ${doc.calls("getName")}")
    }

    @Test
    @DisplayName("a hierarchy with @Delegate is unknown: its generated methods are candidates too")
    fun delegateMakesHierarchyUnknown() {
        val doc = build(
            """
            class A {
                @Delegate List items = []
                void add(String s) {}
                void run() { this.add(5) }
            }
            """,
        )
        assertTrue(doc.calls("add").none { it.symbol == doc.def("A", "add(String)") }, "got: ${doc.calls("add")}")
    }

    @Test
    @DisplayName("a class with methodMissing is dynamic: unmatched calls go there, so none are credited")
    fun methodMissingMakesCallsUnresolved() {
        val doc = build(
            """
            class D {
                void go(int x) {}
                def methodMissing(String name, def args) { null }
                void run() { this.go("s") }
            }
            """,
        )
        assertTrue(doc.calls("go").isEmpty(), "got: ${doc.calls("go")}")
    }

    @Test
    @DisplayName("a static method called through its class resolves by arity")
    fun staticCallResolves() {
        val doc = build(
            """
            class A {
                static void foo(String s) {}
                static void foo(int x, int y) {}
                void run() { A.foo(1, 2) }
            }
            """,
        )
        assertEquals(listOf(doc.def("A", "foo(int,int)")), doc.calls("foo").map { it.symbol })
    }

    @Test
    @DisplayName("a generic method resolves")
    fun genericMethodResolves() {
        val doc = build(
            """
            class A {
                def <T> T id(T value) { value }
                void run() { id(1) }
            }
            """,
        )
        val definition = doc.symbols.single { it.kind == SymbolKind.METHOD && it.name == "id" }
        assertEquals(listOf(definition.symbol), doc.calls("id").map { it.symbol })
    }
}
