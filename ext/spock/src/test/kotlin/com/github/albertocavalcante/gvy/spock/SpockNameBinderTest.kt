// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.spock

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.spock.NameBinding.Ambiguity.INHERITED_NESTED_SCOPE
import com.github.albertocavalcante.gvy.spock.NameBinding.Ambiguity.MULTIPLE_BINDINGS
import com.github.albertocavalcante.nativeapi.ParseRequest
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/** The shared, conservative Groovy name binding used by every Spock source model. */
class SpockNameBinderTest {
    private val parser = GroovyParserFacade(SpockNameBinderTest::class.java.classLoader)
    private val specification = setOf("spock.lang.Specification")

    private fun module(source: String): ModuleNode =
        requireNotNull(parser.parse(ParseRequest(URI.create("file:///S.groovy"), source)).ast)

    private fun ModuleNode.named(name: String): ClassNode = classes.single { it.name == name }

    private fun bind(source: String, spelling: String, scope: String? = null): NameBinding {
        val module = module(source)
        val enclosing = scope?.let { name -> generateSequence(module.named(name)) { it.outerClass } }.orEmpty()
        return SpockNameBinder(module).bind(spelling, enclosing.toList(), specification)
    }

    @Test
    fun `module classes bind by full, package relative and dotted nested spelling`() {
        val source = "package p\nclass Base {}\nclass Outer { static class Inner {} }"
        assertEquals("p.Base", assertIs<NameBinding.ModuleClass>(bind(source, "Base")).node.name)
        assertEquals("p.Base", assertIs<NameBinding.ModuleClass>(bind(source, "p.Base")).node.name)
        assertEquals("p.Outer\$Inner", assertIs<NameBinding.ModuleClass>(bind(source, "Outer.Inner")).node.name)
        assertEquals("p.Outer\$Inner", assertIs<NameBinding.ModuleClass>(bind(source, "p.Outer\$Inner")).node.name)
    }

    @Test
    fun `bare names bind classes nested in an enclosing class only`() {
        val source = "class Outer { static class Base {}\n static class A {} }\nclass Other {}"
        assertIs<NameBinding.ModuleClass>(bind(source, "Base", scope = "Outer\$A"))
        assertSame(NameBinding.Unbound, bind(source, "Base", scope = "Other"))
    }

    @Test
    fun `single type imports and aliases bind their target`() {
        assertEquals(
            NameBinding.Imported("spock.lang.Specification"),
            bind("import spock.lang.Specification as Spec\nclass A {}", "Spec"),
        )
        assertSame(NameBinding.Unbound, bind("import spock.lang.Specification as Spec\nclass A {}", "Specification"))
    }

    @Test
    fun `dotted spellings that are not module classes stay qualified names`() {
        assertEquals(NameBinding.Qualified("com.example.Base"), bind("class A {}", "com.example.Base"))
    }

    @Test
    fun `star imports only offer a target candidate`() {
        assertEquals(
            NameBinding.StarImportOnly("spock.lang.Specification"),
            bind("import spock.lang.*\nclass A {}", "Specification"),
        )
        assertSame(NameBinding.Unbound, bind("import spock.lang.*\nclass A {}", "Unroll"), "Not a requested target")
        assertSame(NameBinding.Unbound, bind("import other.*\nclass A {}", "Specification"))
    }

    @Test
    fun `more than one binding is ambiguous`() {
        val importAndClass = "import spock.lang.Specification as Spec\n" +
            "class Outer { static class Spec {}\n static class A {} }"
        assertEquals(NameBinding.Ambiguous(MULTIPLE_BINDINGS), bind(importAndClass, "Spec", scope = "Outer\$A"))
        assertEquals(NameBinding.Ambiguous(MULTIPLE_BINDINGS), bind("class A {}\nclass A {}", "A"))
    }

    @Test
    fun `an enclosing class with a superclass makes names ambiguous except exact targets`() {
        val source = "class Parent {}\nclass Outer extends Parent { static class S {} }\nclass Base {}"
        assertEquals(NameBinding.Ambiguous(INHERITED_NESTED_SCOPE), bind(source, "Base", scope = "Outer\$S"))
        assertEquals(NameBinding.Ambiguous(INHERITED_NESTED_SCOPE), bind(source, "Base.Inner", scope = "Outer\$S"))
        val iface = "interface I {}\nclass Outer implements I { static class S {} }\nclass Base {}"
        assertEquals(NameBinding.Ambiguous(INHERITED_NESTED_SCOPE), bind(iface, "Base", scope = "Outer\$S"))
        assertEquals(
            NameBinding.Qualified("spock.lang.Specification"),
            bind(source, "spock.lang.Specification", scope = "Outer\$S"),
        )
    }

    @Test
    fun `binary names are normalised`() {
        assertEquals(NameBinding.Qualified("a.B.C"), bind("class X {}", "a.B\$C"))
    }
}
