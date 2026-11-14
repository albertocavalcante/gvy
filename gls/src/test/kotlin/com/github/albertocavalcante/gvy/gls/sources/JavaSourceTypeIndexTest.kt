// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaSourceTypeIndexTest {
    private fun at(content: String, word: String, last: Boolean = false): SourceTypeReference? {
        val offset = if (last) content.lastIndexOf(word) else content.indexOf(word)
        require(offset >= 0)
        val prefix = content.substring(0, offset)
        val line = prefix.count { it == '\n' }
        val character = offset - (prefix.lastIndexOf('\n') + 1)
        return JavaSourceTypeIndex.build(content).at(Position(line, character))
    }

    @Test
    fun `annotation source return type links to same package before java lang`() {
        val content = "package groovy.transform; public @interface CompileStatic { TypeCheckingMode value(); }"
        assertEquals(listOf("groovy.transform.TypeCheckingMode"), at(content, "TypeCheckingMode")?.candidates?.first())
    }

    @Test
    fun `explicit imports bind a type spelling without reinterpreting it as a package name`() {
        val content = "package sample; import groovy.transform.TypeCheckingMode; " +
            "@interface A { TypeCheckingMode value(); }"
        assertEquals(
            listOf(listOf("groovy.transform.TypeCheckingMode")),
            at(content, "TypeCheckingMode", last = true)?.candidates,
        )
    }

    @Test
    fun `static method imports leave same package type candidates available`() {
        val content = "package sample; import static java.util.Collections.emptyList; " +
            "class C { emptyList value; }"
        val candidates = at(content, "emptyList value")?.candidates.orEmpty()

        assertEquals(listOf("java.util.Collections.emptyList"), candidates.first())
        assertEquals(listOf("sample.emptyList"), candidates[1])
    }

    @Test
    fun `static member type imports retain their qualified candidate`() {
        val content = "package sample; import static dependency.Outer.Inner; class C { Inner value; }"
        val candidates = at(content, "Inner value")?.candidates.orEmpty()

        assertEquals(listOf("dependency.Outer.Inner"), candidates.first())
        assertEquals(listOf("sample.Inner"), candidates[1])
    }

    @Test
    fun `strings comments and generic type variables are not external types`() {
        val content = "class A<T> { String text = \"TypeCheckingMode\"; T value; /* TypeCheckingMode */ }"
        assertNull(at(content, "TypeCheckingMode"))
        assertNull(at(content, "T value"))
    }

    @Test
    fun `type parameters only suppress references in their lexical scope`() {
        val content = "import dependency.Item; class Box<Item> { Item value; " +
            "<Item> Item method(Item value) { return value; } } class Other { Item field; }"
        assertNull(at(content, "Item value"))
        assertEquals(listOf(listOf("dependency.Item")), at(content, "Item field")?.candidates)
        val methods = "import dependency.Item; class Box { <Item> Item method() { return null; } Item field; }"
        assertNull(at(methods, "Item method"))
        assertEquals(listOf(listOf("dependency.Item")), at(methods, "Item field")?.candidates)
    }

    @Test
    fun `supertype headers resolve outside their own member scope`() {
        val content = "import dependency.Base; class Model extends Base { static class Base {} Base field; }"
        assertEquals(listOf(listOf("dependency.Base")), at(content, "Base {")?.candidates)
        assertNull(at(content, "Base field"))
        val implemented = "import dependency.Parent; class Model implements Parent { static class Parent {} }"
        assertEquals(listOf(listOf("dependency.Parent")), at(implemented, "Parent {")?.candidates)
    }

    @Test
    fun `enums and records do not guess imported types shadowed by inherited members`() {
        val enumSource = "import external.Mode; interface Parent { enum Mode { ON } } " +
            "enum Child implements Parent { VALUE; Mode field; }"
        val recordSource = "import external.Mode; interface Parent { enum Mode { ON } } " +
            "record Child() implements Parent { Mode field() { return null; } }"
        assertNull(at(enumSource, "Mode field"))
        assertNull(at(recordSource, "Mode field"))
    }

    @Test
    fun `variables shadow type names in expression positions`() {
        val content = "class A { Object TypeCheckingMode; Object f() { return TypeCheckingMode.PASS; } }"
        assertNull(at(content, "TypeCheckingMode", last = true))
    }

    @Test
    fun `unicode and tabs never publish a range for different source text`() {
        val content = "/*😀*/\t@interface A { TypeCheckingMode value(); }"
        val reference = at(content, "TypeCheckingMode")
        assertTrue(reference != null)
        assertEquals(content.indexOf("TypeCheckingMode"), reference.range.startCharacter)
    }

    @Test
    fun `unmodelled inheritance does not guess an implicitly inherited type`() {
        val content = "class A extends Base { TypeCheckingMode value; }"
        assertNull(at(content, "TypeCheckingMode"))
    }

    @Test
    fun `duplicate explicit imports remain unresolved`() {
        val content = "import a.Mode; import b.Mode; @interface A { Mode value(); }"
        assertTrue(at(content, "Mode value")?.candidates?.isEmpty() == true)
    }

    @Test
    fun `local nested declarations are selected in their lexical scope`() {
        val content = "class A { enum Mode { ON } @interface N { Mode value(); } }"
        val reference = requireNotNull(at(content, "Mode value"))
        val definition = assertNotNull(reference.localDefinition)
        assertEquals(content.indexOf("Mode"), definition.nameRange.startCharacter)
    }

    @Test
    fun `inherited member types do not become explicit import guesses`() {
        val content = "import external.Mode; class A extends Base { Mode value; }"
        assertNull(at(content, "Mode value"))
    }

    @Test
    fun `local type declarations do not become external type guesses`() {
        val content = "class A { void f() { class Mode {} Mode value; } }"
        assertNull(at(content, "Mode value"))
    }

    @Test
    fun `oversized syntax does not allocate an unbounded index`() {
        val content = " ".repeat(1024 * 1024) + "@interface A { Mode value(); }"
        assertNull(at(content, "Mode"))
    }

    @Test
    fun `candidate evidence cannot be mutated through an index result`() {
        val content = "@interface A { Mode value(); }"
        val reference = requireNotNull(at(content, "Mode"))
        kotlin.test.assertFailsWith<UnsupportedOperationException> {
            (reference.candidates.first() as MutableList<String>).add("wrong.Type")
        }
    }

    @Test
    fun `expression receivers are not guessed to be type names`() {
        val content = "class A { Object f() { return TypeCheckingMode.PASS; } }"
        assertNull(at(content, "TypeCheckingMode"))
    }

    @Test
    fun `qualified type package prefixes are not separate type references`() {
        val content = "package sample; class C { other.lib.Foo value; }"
        assertNull(at(content, "other"))
        assertNull(at(content, "lib"))
        assertEquals(
            listOf("other.lib.Foo", "sample.other.lib.Foo"),
            at(content, "Foo")?.candidates?.first(),
        )
    }

    @Test
    fun `qualified type arguments remain type references when qualifier scopes are omitted`() {
        val content = "package sample; class C { Outer<Argument>.Inner value; }"
        assertNull(at(content, "Outer"))
        assertTrue(at(content, "Argument") != null)
        assertTrue(at(content, "Inner") != null)
    }

    @Test
    fun `oversized package names reject the index before prefix multiplication`() {
        val text = "package " + "p".repeat(4097) + "; class C { Mode value; }"
        assertNull(at(text, "Mode"))
    }

    @Test
    fun `many individually valid candidate prefixes cannot exceed the aggregate index budget`() {
        val prefix = "p".repeat(4000)
        val imports = (0 until 16).joinToString(" ") { "import $prefix.q$it.*;" }
        val fields = (0 until 100).joinToString(" ") { "Type$it field$it;" }
        val text = "package sample; $imports class C { $fields }"
        assertTrue(text.length < 1024 * 1024)
        assertNull(at(text, "Type0"), "An exhausted index is wholly unknown, not a truncated candidate set")
    }
}
