// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * A nested type spans its type arguments, and a type parameter redirects to its bound: neither may lend a token
 * spelled like the renamed class to that class (#2438 review).
 */
class ClassRenameNestedTypeArgumentsTest {
    @TempDir
    lateinit var workspace: Path

    private val rename by lazy { SingleFileRename(workspace) }

    private fun renamed(content: String, position: Position, newName: String): String =
        runBlocking { rename.apply(content, position, newName) }.getOrThrow()

    /** The refusal rename and prepareRename both give at [position]. */
    private fun refusal(content: String, position: Position): String {
        val failure = runBlocking { rename.apply(content, position, "Renamed") }.exceptionOrNull()
        val refused = assertInstanceOf(RenameRefusedException::class.java, failure, "rename: $failure")
        assertEquals(ResponseErrorCode.RequestFailed.value, refused.responseError.code)
        val prepared = runBlocking { rename.prepare(content, position) }
        val preparedRefusal =
            assertInstanceOf(RenameRefusedException::class.java, prepared.exceptionOrNull(), "prepare: $prepared")
        assertEquals(refused.message, preparedRefusal.message)
        return refused.responseError.message
    }

    private fun at(content: String, needle: String, offset: Int = 0): Position {
        val index = content.indexOf(needle).also { check(it >= 0) { needle } } + offset
        val before = content.substring(0, index)
        return Position(before.count { it == '\n' }, before.length - (before.lastIndexOf('\n') + 1))
    }

    private val foreignArgument = """
        class Date {
            static class Inner<T> {}
            static Inner<java.util.Date> m(Inner<java.util.Date> p) {
                def x = new Inner<java.util.Date>()
                (Inner<java.util.Date>) x
            }
        }
    """.trimIndent()

    @Test
    fun `a foreign class spelled like the renamed one inside a nested type's arguments is left alone`() {
        val result = renamed(foreignArgument, Position(0, 7), "Day")

        assertEquals(foreignArgument.replaceFirst("class Date", "class Day"), result)
    }

    @Test
    fun `a same-named nested class of another class inside a nested type's arguments is left alone`() {
        val content = "class Node { static class Inner<T> {}; Inner<Tree.Node> f }\n" +
            "class Tree { static class Node {}; static class Leaf {} }\n"

        val result = renamed(content, Position(0, 7), "Leaf")

        assertEquals(content.replaceFirst("class Node", "class Leaf"), result)
    }

    @Test
    fun `a rename starting on the foreign class inside a nested type's arguments is refused`() {
        val onForeign = at(foreignArgument, "java.util.Date> m", offset = "java.util.".length + 1)

        assertEquals(
            "Cannot rename 'Date': it is not declared in this file, and renaming across files is not supported yet",
            refusal(foreignArgument, onForeign),
        )
    }

    @Test
    fun `a rename starting on a type parameter bounded by a class is refused and never renames the bound`() {
        val content = "class Service {}\nclass Box<T extends Service> { T item }\n"
        val reason = "Cannot rename 'T': renaming a type parameter is not supported yet"

        assertEquals(reason, refusal(content, at(content, "T item")))
        assertEquals(reason, refusal(content, at(content, "T extends")))
    }
}
