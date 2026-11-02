package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.URI

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

    private fun build(code: String): SemanticDocument {
        if (phase == Phases.CONVERSION) {
            val sourceUnit = SourceUnit("Test.groovy", code.trimIndent(), CompilerConfiguration(), null, null)
            sourceUnit.parse()
            sourceUnit.completePhase()
            sourceUnit.convert()
            return SemanticDocumentBuilder(sourceUnit.ast, uri).build()
        }
        val unit = CompilationUnit(CompilerConfiguration())
        unit.addSource("Test.groovy", code.trimIndent())
        // Like production, index whatever AST exists even when resolution reports errors (e.g. other files).
        runCatching { unit.compile(phase) }
        return SemanticDocumentBuilder(unit.ast.modules.single(), uri).build()
    }

    private fun SemanticDocument.calls(name: String) = occurrences.filter {
        it.role == OccurrenceRole.CALL && it.symbol.contains("#$name(")
    }

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
    @DisplayName("calls with statically typed arguments resolve to the matching overload")
    fun callSitesPickTheRightOverload() {
        val doc = build(
            """
            class A {
                void add(int a) {}
                void add(String a) {}
                void run() {
                    add(1)
                    add("s")
                }
            }
            """,
        )
        val called = doc.calls("add").map { it.symbol }
        assertEquals(listOf("A#add(int).", "A#add(String)."), called)
    }

    @Test
    @DisplayName("a call to a class from another file uses the exact static argument types")
    fun externalReceiverUsesStaticSignature() {
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
        )
        val called = (doc.calls("add") + doc.calls("reset")).map { it.symbol }
        assertEquals(listOf("Calculator#add(int,int).", "Calculator#reset()."), called)
    }

    @Test
    @DisplayName("ambiguous overloads with dynamic arguments stay unresolved, unique arity still resolves")
    fun dynamicArgumentsPolicy() {
        val ambiguous = build(
            """
            class A {
                void add(int a) {}
                void add(String a) {}
                void run(def y) { add(y) }
            }
            """,
        )
        assertTrue(ambiguous.calls("add").isEmpty(), "two same-arity candidates and a dynamic arg: no guess")

        val byArity = build(
            """
            class A {
                void add(int a) {}
                void add(int a, int b) {}
                void run(def y) { add(y) }
            }
            """,
        )
        assertEquals(listOf("A#add(int)."), byArity.calls("add").map { it.symbol })
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
    @DisplayName("a call is credited to the inherited overload that fits, not a subclass overload of the same arity")
    fun inheritedOverloadWins() {
        val doc = build(
            """
            class Base { void foo(int x) {} }
            class A extends Base {
                void foo(String s) {}
                void run() { foo(1) }
            }
            """,
        )
        assertEquals(
            listOf("Base#foo(int)."),
            doc.calls("foo").map { it.symbol.substringAfter("/").removePrefix("") }
                .map { it.substringAfterLast('/') },
        )
    }

    @Test
    @DisplayName("calls to an inherited method resolve to the declaring class")
    fun inheritedMethodsResolveToDeclaringClass() {
        val doc = build(
            """
            class A { void foo() {} }
            class B extends A {
                void run() { foo() }
            }
            class C {
                void use() { new B().foo() }
            }
            """,
        )
        val symbols = doc.calls("foo").map { it.symbol.substringAfterLast('/') }
        assertEquals(listOf("A#foo().", "A#foo()."), symbols)
    }

    @Test
    @DisplayName("an implicit-this call inside a closure resolves to the enclosing class")
    fun implicitThisInClosure() {
        val doc = build(
            """
            class A {
                void foo(int x) {}
                void run() { def c = { foo(1) }; c() }
            }
            """,
        )
        assertEquals(listOf("A#foo(int)."), doc.calls("foo").map { it.symbol.substringAfterLast('/') })
    }

    @Test
    @DisplayName("a static method called through its class resolves")
    fun staticCallResolves() {
        val doc = build(
            """
            class A {
                static void foo(String s) {}
                static void foo(int x) {}
                void run() { A.foo(1) }
            }
            """,
        )
        assertEquals(listOf("A#foo(int)."), doc.calls("foo").map { it.symbol.substringAfterLast('/') })
    }

    @Test
    @DisplayName("a generic method resolves and a generic parameter accepts any argument")
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

    @Test
    @DisplayName("a call with fewer arguments than parameters resolves to the method with defaults")
    fun defaultParameterCall() {
        val doc = build(
            """
            class A {
                void foo(int x, int y = 2) {}
                void run() { foo(1) }
            }
            """,
        )
        assertEquals(1, doc.calls("foo").size)
        assertTrue(doc.calls("foo").single().symbol.endsWith("A#foo(int,int)."))
    }
}
