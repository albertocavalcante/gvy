// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Where a class rename finds each constructor's name: only in code between the constructor's annotations and its
 * parameters, never in a string or comment that spells `Name(`; and where a rename may start on a constructor or a
 * `new` call (see #2434).
 */
class ConstructorNameLocationTest {
    @TempDir
    lateinit var workspace: Path

    private val rename by lazy { SingleFileRename(workspace) }

    private fun renamed(content: String, position: Position, newName: String = "Worker"): String =
        runBlocking { rename.apply(content, position, newName) }.getOrThrow()

    private fun failure(content: String, position: Position, newName: String = "Worker"): ResponseErrorException =
        assertInstanceOf(
            ResponseErrorException::class.java,
            runBlocking { rename.apply(content, position, newName) }.exceptionOrNull(),
        )

    private fun prepared(content: String, position: Position) = runBlocking { rename.prepare(content, position) }

    private fun range(line: Int, start: Int, end: Int) = Range(Position(line, start), Position(line, end))

    @Test
    fun `a constructor name spelled inside a slashy string of an annotation is not the name`() {
        val content = "class Service {\n    @SuppressWarnings(/x) Service(/) Service(int b) {}\n}\n" +
            "def s = new Service(1)\n"

        val result = renamed(content, Position(0, 7))

        val expected = "class Worker {\n    @SuppressWarnings(/x) Service(/) Worker(int b) {}\n}\n" +
            "def s = new Worker(1)\n"
        assertEquals(expected, result)
    }

    @Test
    fun `a constructor name spelled inside a GString of an annotation closure is not the name`() {
        val content = "@interface Requires { Class value() }\nclass Service {\n" +
            "    @Requires({ \"\${\") Service(\"}\" == \"x\" })\n    Service(String a) {}\n}\n" +
            "def s = new Service('a')\n"

        val result = renamed(content, Position(1, 7))

        val expected = "@interface Requires { Class value() }\nclass Worker {\n" +
            "    @Requires({ \"\${\") Service(\"}\" == \"x\" })\n    Worker(String a) {}\n}\n" +
            "def s = new Worker('a')\n"
        assertEquals(expected, result)
    }

    @Test
    fun `a constructor name spelled inside a dollar-slashy annotation string is not the name`() {
        val content = "@interface Ann { String value() }\nclass Service {\n" +
            "    @Ann(\$/x) Service(\$/\$) Service() {}\n}\ndef s = new Service()\n"

        val result = renamed(content, Position(1, 7))

        val expected = "@interface Ann { String value() }\nclass Worker {\n" +
            "    @Ann(\$/x) Service(\$/\$) Worker() {}\n}\ndef s = new Worker()\n"
        assertEquals(expected, result)
    }

    @Test
    fun `a record's compact constructor after a slashy string with a quote is still refused`() {
        val content = """
            record Point(int x) {
                static boolean m(String s) { s ==~ /it's/ }
                Point { assert x > 0 }
                static String q() { 'x' }
            }
        """.trimIndent()

        val refused = assertInstanceOf(RenameRefusedException::class.java, failure(content, Position(0, 8)))

        assertEquals(ResponseErrorCode.RequestFailed.value, refused.responseError.code)
        assertEquals(
            "Cannot rename 'Point': renaming a record with a compact constructor (line 3) is not supported yet",
            refused.message,
        )
    }

    @Test
    fun `a constructor with type parameters is renamed with the bound spelled like the class`() {
        val content = "class Service {\n    public <T extends Service> Service(T t) { }\n}\n"

        val result = renamed(content, Position(0, 7))

        assertEquals("class Worker {\n    public <T extends Worker> Worker(T t) { }\n}\n", result)
    }

    @Test
    fun `a def constructor is renamed`() {
        val content = "class Service {\n    def Service() { }\n}\ndef s = new Service()\n"

        val result = renamed(content, Position(0, 7))

        assertEquals("class Worker {\n    def Worker() { }\n}\ndef s = new Worker()\n", result)
    }

    @Test
    fun `a constructor whose parameter defaults to a constant of the class is renamed once`() {
        val content = "class Service {\n    static final String NAME = 'n'\n" +
            "    Service(String s = Service.NAME) { }\n}\n"

        val result = renamed(content, Position(0, 7))

        assertEquals(content.replace("Service", "Worker"), result)
    }

    @Test
    fun `a class and its constructor on one line are both renamed`() {
        val content = "class S { S() {} }\ndef s = new S()\n"

        assertEquals("class T { T() {} }\ndef s = new T()\n", renamed(content, Position(0, 6), "T"))
    }

    @Test
    fun `a class whose name is outside the basic plane is renamed in a CRLF file`() {
        val name = "𝐀Svc"
        val content = "class $name {\r\n    $name(int a) { }\r\n}\r\ndef s = new $name(1)\r\n"

        val result = renamed(content, Position(0, 8), "Renamed")

        assertEquals(content.replace(name, "Renamed"), result)
    }

    @Test
    fun `only the renamed class's constructors change when two classes declare constructors`() {
        val content = "class Service {\n    Service(int a) { }\n}\nclass Other {\n    Other(int b) { }\n" +
            "    Other() { this(new Service(1).hashCode()) }\n}\n"

        val service = renamed(content, Position(0, 7))
        val other = renamed(content, Position(3, 7), "Thing")

        assertEquals(
            "class Worker {\n    Worker(int a) { }\n}\nclass Other {\n    Other(int b) { }\n" +
                "    Other() { this(new Worker(1).hashCode()) }\n}\n",
            service,
        )
        assertEquals(content.replace("Other", "Thing"), other)
    }

    private val newOnItsOwnLine = "class Service {\n    Service(String n) { }\n}\ndef a = new\n    Service('a')\n"

    @Test
    fun `on a new keyword whose type is on the next line, prepare offers nothing and rename refuses`() {
        val onNew = Position(3, 9)

        assertEquals(Result.success(null), prepared(newOnItsOwnLine, onNew))
        val refused = failure(newOnItsOwnLine, onNew)
        assertEquals(ResponseErrorCode.InvalidRequest.value, refused.responseError.code)
        assertEquals("No name to rename at the cursor", refused.message)
    }

    @Test
    fun `on the type of a new expression split over lines, prepare and rename work`() {
        val onType = Position(4, 6)

        assertEquals(
            Result.success(PrepareRenameResult(range(4, 4, 11), "Service")),
            prepared(newOnItsOwnLine, onType),
        )
        assertEquals(newOnItsOwnLine.replace("Service", "Worker"), renamed(newOnItsOwnLine, onType))
    }

    private val publicConstructor = "class Service {\n    public Service(String n) { }\n}\n"

    @Test
    fun `a rename on a constructor's modifier or parenthesis refuses as prepare offers nothing there`() {
        for (position in listOf(Position(1, 6), Position(1, 18))) {
            assertEquals(Result.success(null), prepared(publicConstructor, position), "prepare at $position")
            val refused = failure(publicConstructor, position)
            assertEquals(ResponseErrorCode.InvalidRequest.value, refused.responseError.code, "rename at $position")
            assertEquals("No name to rename at the cursor", refused.message, "rename at $position")
        }
    }

    @Test
    fun `a rename on a constructor's name renames the class`() {
        assertEquals(publicConstructor.replace("Service", "Worker"), renamed(publicConstructor, Position(1, 13)))
    }
}
