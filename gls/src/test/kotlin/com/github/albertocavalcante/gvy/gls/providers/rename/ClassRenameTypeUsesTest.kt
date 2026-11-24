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
 * A class rename edits every use of the class the document spells, each once, or refuses the whole rename (#2438).
 * The inventory fixtures below spell the class in every syntactic position a Groovy 4 type name can take.
 */
class ClassRenameTypeUsesTest {
    @TempDir
    lateinit var workspace: Path

    private val rename by lazy { SingleFileRename(workspace) }

    private fun renamed(content: String, position: Position, newName: String): String =
        runBlocking { rename.apply(content, position, newName) }.getOrThrow()

    private fun refusal(content: String, position: Position, newName: String = "Worker"): String {
        val failure = runBlocking { rename.apply(content, position, newName) }.exceptionOrNull()
        val refused = assertInstanceOf(RenameRefusedException::class.java, failure, "rename: $failure")
        assertEquals(ResponseErrorCode.RequestFailed.value, refused.responseError.code)
        val prepared = runBlocking { rename.prepare(content, position) }.exceptionOrNull()
        assertEquals(refused.message, assertInstanceOf(RenameRefusedException::class.java, prepared).message)
        return refused.responseError.message
    }

    /** The start of the [occurrence]th (0-based) [needle] in [content], then [offset] characters on. */
    private fun at(content: String, needle: String, occurrence: Int = 0, offset: Int = 0): Position {
        var index = -1
        repeat(occurrence + 1) { index = content.indexOf(needle, index + 1).also { check(it >= 0) { needle } } }
        val before = content.substring(0, index + offset)
        return Position(before.count { it == '\n' }, before.length - (before.lastIndexOf('\n') + 1))
    }

    private val classAndScriptUses = """
        package p

        @interface Tag { Class value() }
        interface Api { }
        class Service implements Api {
            static final String NAME = 'n'
            Service() { }
            Service(Service other) { }
            static Service make() { new Service() }
            def <T extends Service> T pick(T t) { t }
        }
        class Derived extends Service implements Comparable<Service> {
            int compareTo(Service o) { 0 }
        }
        class Holder<T extends Service> {
            List<Service> list
            Map<String, List<? extends Service>> map
            Service[] array
            p.Service qualified
            Service field
            void take(Service q, Service... rest) { }
        }
        @Tag(Service) class Tagged { }
        def anonymous = new Service() { }
        def anonymousWithArguments = new Service(null) { String toString() { 'x' } }
        Service local = null
        def (Service first, Service second) = [null, null]
        def cast = (Service) null
        def coerced = null as Service
        def check = anonymous instanceof Service
        def negated = anonymous !instanceof Service
        def constant = Service.NAME
        def literal = Service.class
        def call = Service.make()
        def reference = Service::new
        def pointer = Service.&make
        def closure = { Service x -> x }
        def lambda = (Service y) -> y
        for (Service z in []) { }
        for (Service w : []) { }
        def made = new Service[2]
        def typed = new ArrayList<Service>()
        def text = "${'$'}{Service.NAME}"
    """.trimIndent()

    @Test
    fun `every use of a class in its declarations, signatures and expressions is renamed once`() {
        val result = renamed(classAndScriptUses, at(classAndScriptUses, "Service implements"), "Worker")

        assertEquals(classAndScriptUses.replace("Service", "Worker"), result)
    }

    private val exceptionAndRecordUses = """
        class Failure extends RuntimeException { }
        class Use {
            void m() throws Failure {
                try { } catch (Failure | IllegalStateException e) { }
                try { } catch (Failure f) { }
            }
        }
        record Holder(Failure failure) { }
        @interface Ann { Class<? extends Failure> value() }
        @Ann(Failure) class Annotated { }
        def failures = [new Failure()] as Failure[]
    """.trimIndent()

    @Test
    fun `an exception class is renamed in throws, catch, multi-catch, record components and class bounds`() {
        val result = renamed(exceptionAndRecordUses, at(exceptionAndRecordUses, "Failure"), "Fault")

        assertEquals(exceptionAndRecordUses.replace("Failure", "Fault"), result)
    }

    private val sealedAndAnnotation = """
        sealed class Shape permits Square { }
        final class Square extends Shape { }
        @interface Marker { }
        @Marker class Marked { @Marker String field }
    """.trimIndent()

    @Test
    fun `a permitted subclass and an annotation type are renamed where they are used`() {
        val square = renamed(sealedAndAnnotation, at(sealedAndAnnotation, "Square", occurrence = 1), "Box")
        val marker = renamed(sealedAndAnnotation, at(sealedAndAnnotation, "Marker"), "Label")

        assertEquals(sealedAndAnnotation.replace("Square", "Box"), square)
        assertEquals(sealedAndAnnotation.replace("Marker", "Label"), marker)
    }

    @Test
    fun `a nested class's qualifier and imports of a class declared in the file are renamed`() {
        val content = """
            package p
            import p.Service
            import static p.Service.NAME
            class Service {
                static final String NAME = 'n'
                static class Inner { }
            }
            Service.Inner inner = new Service.Inner()
            def n = NAME
        """.trimIndent()

        val result = renamed(content, at(content, "Service {"), "Worker")

        assertEquals(content.replace("Service", "Worker"), result)
    }

    /** The start of the class name at a parameter type, a superclass, a type argument, an anonymous subclass, ... */
    private val typeUseStarts by lazy {
        listOf(
            at(classAndScriptUses, "Service other"),
            at(classAndScriptUses, "extends Service", offset = "extends ".length),
            at(classAndScriptUses, "Service> {"),
            at(classAndScriptUses, "new Service() { }", offset = "new ".length),
            at(classAndScriptUses, "Service> T pick"),
            at(classAndScriptUses, "Service[] array"),
            at(classAndScriptUses, "Service local"),
            at(classAndScriptUses, "Service) null"),
            at(classAndScriptUses, "Service[2]"),
            at(classAndScriptUses, "Service x"),
        )
    }

    @Test
    fun `a rename starting on any type use renames the class with exactly the edit from its declaration`() {
        val fromDeclaration = renamed(classAndScriptUses, at(classAndScriptUses, "Service implements"), "Worker")

        for (start in typeUseStarts + typeUseStarts.map { Position(it.line, it.character + 3) }) {
            assertEquals(fromDeclaration, renamed(classAndScriptUses, start, "Worker"), "from $start")
        }
    }

    @Test
    fun `prepare rename offers the type name under the cursor at every type use`() {
        for (start in typeUseStarts) {
            val name = Range(start, Position(start.line, start.character + "Service".length))

            val prepared = runBlocking { rename.prepare(classAndScriptUses, Position(start.line, start.character + 2)) }

            assertEquals(Result.success(PrepareRenameResult(name, "Service")), prepared, "at $start")
        }
    }

    @Test
    fun `names that are not the class are left alone or refuse when they cannot be told apart`() {
        val content = """
            class Service {
                String Service() { 'a method' }
            }
            def Service = new Service()
            println Service
            println Service.Service()
            // Service
            /** {@link Service} */
            def strings = ['Service', "Service", /Service/, ${'$'}/Service/${'$'}, '''Service''']
            @SuppressWarnings('Service') def annotated = 1
            java.util.Date date = null
        """.trimIndent()

        val result = renamed(content, at(content, "Service"), "Worker")

        val expected = """
            class Worker {
                String Service() { 'a method' }
            }
            def Service = new Worker()
            println Service
            println Service.Service()
            // Service
            /** {@link Service} */
            def strings = ['Service', "Service", /Service/, ${'$'}/Service/${'$'}, '''Service''']
            @SuppressWarnings('Service') def annotated = 1
            java.util.Date date = null
        """.trimIndent()
        assertEquals(expected, result)
    }

    @Test
    fun `a class named like a class of another package used by qualified name renames only itself`() {
        val content = "class Date { }\nDate mine = new Date()\njava.util.Date theirs = new java.util.Date()\n"

        val result = renamed(content, Position(0, 7), "Day")

        assertEquals("class Day { }\nDay mine = new Day()\njava.util.Date theirs = new java.util.Date()\n", result)
    }

    @Test
    fun `a name no scan can classify refuses the whole rename`() {
        val mapKey = "class Service { }\ndef m = [Service: 1]\nService s = null\n"
        val field = "class Service {\n    String Service = 'x'\n}\n"
        val property = "class Service { }\ndef x = [:]\nx.Service = 1\n"
        val parameter = "class Service {\n    void m(Service Service) { }\n}\n"

        val atLine2 = "Cannot rename 'Service': a use at line 2 could not be classified"
        assertEquals(atLine2, refusal(mapKey, Position(0, 7)))
        assertEquals(atLine2, refusal(field, Position(0, 7)))
        assertEquals(atLine2, refusal(parameter, Position(0, 7)))
        assertEquals(
            "Cannot rename 'Service': a use at line 3 could not be classified",
            refusal(property, Position(0, 7)),
        )
    }

    // FIXME(#2438): a GString shorthand (`"$Service.NAME"`) or a package-qualified expression (`p.Service.NAME`)
    //   fails the strict reference scan, which reports ContentModified ("Source changed or is unavailable") although
    //   nothing changed, as it does for an explicit import alias. Nothing is edited, so it is safe; the reason is
    //   wrong. This test pins that.
    //   See: https://github.com/albertocavalcante/gvy/issues/2438
    @Test
    fun `a GString shorthand or a qualified expression naming the class makes the rename fail without an edit`() {
        val head = "package p\nclass Service { static String NAME = 'n' }\n"
        val uses = listOf("def t = \"\$Service.NAME\"\n", "def t = \"\$p.Service.NAME\"\n", "def t = p.Service.NAME\n")

        for (use in uses) {
            val failure = runBlocking { rename.apply(head + use, Position(1, 7), "Worker") }.exceptionOrNull()

            val error = assertInstanceOf(ResponseErrorException::class.java, failure, use)
            assertEquals(ResponseErrorCode.ContentModified.value, error.responseError.code, use)
            assertEquals("Source changed or is unavailable during rename: ${rename.uri}", error.message, use)
        }
    }

    @Test
    fun `a class whose name is outside the basic plane is renamed at its type uses in a CRLF file`() {
        val name = "𝐀Svc"
        val content = "class $name { }\r\nclass D extends $name {\r\n    $name m(List<$name> xs) { null }\r\n}\r\n"

        val result = renamed(content, Position(0, 8), "Renamed")

        assertEquals(content.replace(name, "Renamed"), result)
    }

    @Test
    fun `members that transforms generate with the class's type spell nothing and do not refuse the rename`() {
        val content = """
            import groovy.transform.*
            @Canonical @Sortable @AutoClone @groovy.transform.builder.Builder
            class Service {
                String name
                Service copy(Service other) { other }
            }
            @Singleton class Registry { Service service }
            @Immutable class Value { int n }
            def s = new Service('a')
            Value v = new Value(1)
        """.trimIndent()

        val service = renamed(content, at(content, "Service {"), "Worker")
        val value = renamed(content, at(content, "Value {"), "Amount")

        assertEquals(content.replace("Service", "Worker"), service)
        assertEquals(content.replace("Value", "Amount"), value)
    }

    @Test
    fun `renaming one of two classes leaves the other's uses alone`() {
        val content = "class A { }\nclass B {\n    A a\n    B b\n    B m(A x, List<B> y) { null }\n}\n"

        val result = renamed(content, Position(0, 6), "C")

        assertEquals("class C { }\nclass B {\n    C a\n    B b\n    B m(C x, List<B> y) { null }\n}\n", result)
    }
}
