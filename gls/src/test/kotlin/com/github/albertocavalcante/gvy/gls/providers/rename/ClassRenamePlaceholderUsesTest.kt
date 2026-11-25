// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path

/**
 * A type parameter spelled like the renamed class is never the class, however the use reaches it: a class literal, a
 * static call, a constructor call, a cast, `as`, `instanceof` or an array type. The compiler gives such a use a
 * placeholder that redirects to the parameter's bound, which may be the class itself, so a use through one must
 * refuse the rename or stay untouched, never be edited (#2443 review).
 */
class ClassRenamePlaceholderUsesTest {
    @TempDir
    lateinit var workspace: Path

    /** What renaming the class `T` declared at the first `class T` of [source] to `Tee` must do. */
    sealed interface Expected {
        /** The whole text after the rename. */
        data class Text(val text: String) : Expected

        /** A refusal, for no edit at all is better than a wrong one. */
        data object Refusal : Expected
    }

    class Case(val name: String, val source: String, val expected: Expected) {
        override fun toString(): String = name
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `renaming the class T edits it and never a type parameter named T`(case: Case) {
        val offset = case.source.indexOf("class T").also { check(it >= 0) } + "class ".length
        val before = case.source.substring(0, offset)
        val start = Position(before.count { it == '\n' }, before.length - (before.lastIndexOf('\n') + 1))

        val result = runBlocking { SingleFileRename(workspace).apply(case.source, start, "Tee") }

        when (val expected = case.expected) {
            is Expected.Text -> assertEquals(expected.text, result.getOrThrow())
            Expected.Refusal -> {
                val refused = assertInstanceOf(RenameRefusedException::class.java, result.exceptionOrNull(), "$result")
                assertTrue(refused.message.orEmpty().startsWith("Cannot rename 'T'"), refused.message)
            }
        }
    }

    companion object {
        private fun renamed(source: String, vararg edits: Pair<String, String>): Expected.Text =
            Expected.Text(edits.fold(source) { text, (from, to) -> text.replace(from, to) })

        private val declaration = "class T {}" to "class Tee {}"

        /** Where the compiler scopes `T` to the class (a static context) and where to the type parameter. */
        private fun scoping(): List<Case> = listOf(
            "static nested class inside a generic class sees the class" to
                "class T {}\nclass Box<T> { static class In { T s } }\n",
            "static method of a generic class sees the class" to
                "class T {}\nclass Box<T> { static T sm() { null } }\n",
        ).map { (name, source) ->
            val classUses = arrayOf(declaration, "{ T s }" to "{ Tee s }", "static T sm" to "static Tee sm")
            Case(name, source, renamed(source, *classUses))
        } + listOf(
            "inner class of a generic class sees the parameter" to "class T {}\nclass Box<T> { class In { T s } }\n",
            "anonymous class in a generic method sees the parameter" to
                "class T {}\nclass Box { def <T> Object m() { new Object() { T s } } }\n",
            "recursive bound of the parameter" to "class T {}\nclass Box<T extends Comparable<T>> { T x }\n",
        ).map { (name, source) -> Case(name, source, renamed(source, declaration)) } + listOf(
            Case(
                "method parameter shadows class parameter shadows class",
                "class T {}\nclass Box<T> { T a; def <T> T id(T x) { x } }\nclass Out { T z }\n",
                renamed(
                    "class T {}\nclass Box<T> { T a; def <T> T id(T x) { x } }\nclass Out { T z }\n",
                    declaration,
                    "{ T z }" to "{ Tee z }",
                ),
            ),
        )

        /** A use of `T` in a body, and whether the rename refuses when `T` is bounded by the class or is not. */
        private class Form(val name: String, val body: String, val refusedBounded: Boolean, val refusedFree: Boolean)

        private val forms = listOf(
            Form("a class literal", "T x; def k() { T.class }; def <T> void m() { T.class }", true, true),
            Form("a static call", "def k() { T.foo() }", true, true),
            Form("a constructor call", "def k() { new T() }", false, false),
            Form("a cast", "def k(x) { (T) x }", false, false),
            Form("an as coercion", "def k(x) { x as T }", false, false),
            Form("an instanceof", "def k(x) { x instanceof T }", false, false),
            Form("an array type", "T[] arr", false, false),
        )

        private fun bounded(body: String) = "package pkg\nclass T {}\nclass Box<T extends pkg.T> { $body }\n"

        private fun unbounded(body: String) = "package pkg\nclass T {}\nclass Box<T> { $body }\n"

        @JvmStatic
        fun cases(): List<Case> = buildList {
            for (form in forms) {
                // Either the rename refuses, or only the declaration (and the bound) change: the parameter never does.
                val source = bounded(form.body)
                add(
                    Case(
                        "bounded: ${form.name}",
                        source,
                        if (form.refusedBounded) {
                            Expected.Refusal
                        } else {
                            renamed(source, declaration, "pkg.T" to "pkg.Tee")
                        },
                    ),
                )
                val free = unbounded(form.body)
                add(
                    Case(
                        "unbounded: ${form.name}",
                        free,
                        if (form.refusedFree) Expected.Refusal else renamed(free, declaration),
                    ),
                )
            }
            val nested =
                "class Outer { static class T {}; static class Box<T extends Outer.T> { def k() { T.class } } }\n"
            add(Case("bounded by a nested class: a class literal", nested, Expected.Refusal))
            addAll(scoping())
        }
    }
}
