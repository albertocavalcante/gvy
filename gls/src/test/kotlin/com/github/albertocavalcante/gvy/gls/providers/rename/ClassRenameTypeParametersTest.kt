// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * A type parameter spelled like the renamed class is proven to be another name, so the class renames and the
 * parameter is untouched; inside a generic declaration the name is the parameter, outside it is the class (#2443).
 */
class ClassRenameTypeParametersTest {
    @TempDir
    lateinit var workspace: Path

    /** Renames the class `T` declared on the first line to `Tee`. */
    private fun renameClassT(content: String): String =
        runBlocking { SingleFileRename(workspace).apply(content, Position(0, 6), "Tee") }.getOrThrow()

    @Test
    fun `a class T is renamed and the type parameter T of a generic class is left alone`() {
        val content = "class T {}\nclass Box<T> { T x }\n"

        assertEquals("class Tee {}\nclass Box<T> { T x }\n", renameClassT(content))
    }

    @Test
    fun `a method type parameter T next to a class T is left alone`() {
        val content = """
            class T {}
            class Util {
                def <T> T id(T x) { x }
                T make() { new T() }
            }
        """.trimIndent() + "\n"

        val expected = content.replaceFirst(
            "class T {}",
            "class Tee {}",
        ).replace("T make() { new T() }", "Tee make() { new Tee() }")
        assertEquals(expected, renameClassT(content))
    }

    @Test
    fun `a class T used outside a generic class that shadows it is renamed, inside it is not`() {
        val content = """
            class T {}
            class Outside { T a }
            class Box<T> { T x; List<T> xs; T[] ys }
            class After { T b }
        """.trimIndent() + "\n"

        val expected = content.replaceFirst("class T {}", "class Tee {}")
            .replace("class Outside { T a }", "class Outside { Tee a }")
            .replace("class After { T b }", "class After { Tee b }")
        assertEquals(expected, renameClassT(content))
    }

    @Test
    fun `a bound that names the class T is renamed while the parameter it bounds is not`() {
        val content = "class T {}\nclass Box<X extends T> { X x; T y }\n"

        assertEquals("class Tee {}\nclass Box<X extends Tee> { X x; Tee y }\n", renameClassT(content))
    }
}
