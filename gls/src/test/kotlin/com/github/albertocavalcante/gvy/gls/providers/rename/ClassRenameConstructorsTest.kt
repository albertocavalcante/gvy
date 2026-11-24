// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** A class rename edits the constructors its source declares with the class, or refuses; see #2434. */
class ClassRenameConstructorsTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var documentProvider: DocumentProvider
    private lateinit var renameProvider: RenameProvider
    private lateinit var workspaceRoot: Path

    @BeforeEach
    fun setUp() {
        workspaceRoot = Files.createTempDirectory("groovy-lsp-rename-constructors-test")
        compilationService = GroovyCompilationService()
        compilationService.workspaceManager.initializeWorkspace(workspaceRoot)
        documentProvider = DocumentProvider()
        renameProvider = RenameProvider(compilationService) { documentProvider.get(it) }
    }

    private suspend fun renameAndApply(content: String, position: Position, newName: String): String {
        val uri = workspaceRoot.resolve("Rename.groovy").toUri().toString()
        val javaUri = java.net.URI.create(uri)
        documentProvider.put(javaUri, content)
        compilationService.compile(javaUri, content)
        val edits = renameProvider.provideRename(uri, position, newName).documentChanges
            .filter { it.isLeft && it.left.textDocument.uri == uri }
            .flatMap { it.left.edits }
        return applyTextEdits(content, edits)
    }

    private val serviceWithConstructors = """
        class Service {
            Service(String name) { }
            Service(String name, int size) { }
        }
        def a = new Service('a')
        def b = new Service('b', 1)
        def c = new Service('c')
    """.trimIndent()

    private val renamedServiceCalls = """
        def a = new Worker('a')
        def b = new Worker('b', 1)
        def c = new Worker('c')
    """.trimIndent()

    private fun prepareRename(content: String, position: Position) = runBlocking {
        val uri = workspaceRoot.resolve("Rename.groovy").toUri().toString()
        val javaUri = java.net.URI.create(uri)
        documentProvider.put(javaUri, content)
        compilationService.compile(javaUri, content)
        runCatching { renameProvider.providePrepareRename(uri, position) }
    }

    /** The whole edit a rename at each of [positions] produces, from one compilation of [content]. */
    private suspend fun renameEditsAt(
        content: String,
        newName: String,
        vararg positions: Position,
    ): List<WorkspaceEdit> {
        val uri = workspaceRoot.resolve("Rename.groovy").toUri().toString()
        val javaUri = java.net.URI.create(uri)
        documentProvider.put(javaUri, content)
        compilationService.compile(javaUri, content)
        return positions.map { renameProvider.provideRename(uri, it, newName) }
    }

    private fun range(line: Int, start: Int, end: Int) = Range(Position(line, start), Position(line, end))

    /** The start of the first [needle] on [line] of [content]. */
    private fun positionOf(content: String, line: Int, needle: String): Position {
        val column = content.lines()[line].indexOf(needle)
        check(column >= 0) { "'$needle' not on line $line of: $content" }
        return Position(line, column)
    }

    private val renamedService =
        "class Worker {\n    Worker(String name) { }\n    Worker(String name, int size) { }\n}\n" + renamedServiceCalls

    @Test
    fun `renaming a class with constructors renames the class, each constructor and each new expression once`(): Unit =
        runBlocking {
            val result = renameAndApply(serviceWithConstructors, Position(0, 7), "Worker")

            assertEquals(renamedService, result)
        }

    @Test
    fun `a class rename edits exactly the class name, the constructor names and the new sites`(): Unit = runBlocking {
        val (edit) = renameEditsAt(serviceWithConstructors, "Worker", Position(0, 7))

        val uri = workspaceRoot.resolve("Rename.groovy").toUri().toString()
        assertEquals(1, edit.documentChanges.size, "one document edit and no file rename: ${edit.documentChanges}")
        val document = edit.documentChanges.single().left
        assertEquals(uri, document.textDocument.uri)
        val version: Int? = document.textDocument.version
        assertEquals(null, version, "the test's document source has no version")
        assertEquals(
            listOf(
                TextEdit(range(0, 6, 13), "Worker"),
                TextEdit(range(1, 4, 11), "Worker"),
                TextEdit(range(2, 4, 11), "Worker"),
                TextEdit(range(4, 12, 19), "Worker"),
                TextEdit(range(5, 12, 19), "Worker"),
                TextEdit(range(6, 12, 19), "Worker"),
            ),
            document.edits.sortedWith(compareBy({ it.range.start.line }, { it.range.start.character })),
        )
    }

    @Test
    fun `renaming from the new keyword renames the class and keeps the keyword`(): Unit = runBlocking {
        val position = positionOf(serviceWithConstructors, 4, "new")

        val result = renameAndApply(serviceWithConstructors, position, "Worker")

        assertEquals(renamedService, result)
    }

    @Test
    fun `renaming from a constructor declaration renames the class with exactly the edit of the class name`(): Unit =
        runBlocking {
            val (fromClass, fromFirst, fromSecond) = renameEditsAt(
                serviceWithConstructors,
                "Worker",
                Position(0, 7),
                Position(1, 4),
                Position(2, 8),
            )

            assertEquals(fromClass, fromFirst)
            assertEquals(fromClass, fromSecond)
            assertEquals(renamedService, renameAndApply(serviceWithConstructors, Position(1, 4), "Worker"))
        }

    @Test
    fun `prepare rename answers the name under the cursor for the class, a constructor and a new type`(): Unit =
        runBlocking {
            val onNewType = positionOf(serviceWithConstructors, 4, "Service")
            val prepared = listOf(Position(0, 7), Position(1, 4), Position(2, 10), onNewType)
                .map { prepareRename(serviceWithConstructors, it) }

            assertEquals(
                listOf(range(0, 6, 13), range(1, 4, 11), range(2, 4, 11), range(4, 12, 19))
                    .map { Result.success(PrepareRenameResult(it, "Service")) },
                prepared,
            )
        }

    @Test
    fun `prepare rename on the new keyword answers the type name the rename edits there`(): Unit = runBlocking {
        val position = positionOf(serviceWithConstructors, 4, "new")

        assertEquals(
            Result.success(PrepareRenameResult(range(4, 12, 19), "Service")),
            prepareRename(serviceWithConstructors, position),
        )
    }

    @Test
    fun `renaming a class renames annotated constructors and leaves comments, strings and Groovydoc alone`(): Unit =
        runBlocking {
            val content = """
                class Service {
                    /** Builds a {@link Service}. */
                    @Deprecated
                    public Service(String name, int size) { }
                    protected /* Service */ Service(@SuppressWarnings('Service') String name) { }
                    @SuppressWarnings(value = ['Service']) private Service() { }
                }
                def s = [new Service('x', 1), new Service('y')]
            """.trimIndent()

            val result = renameAndApply(content, Position(0, 7), "Worker")

            val expected = """
                class Worker {
                    /** Builds a {@link Service}. */
                    @Deprecated
                    public Worker(String name, int size) { }
                    protected /* Service */ Worker(@SuppressWarnings('Service') String name) { }
                    @SuppressWarnings(value = ['Service']) private Worker() { }
                }
                def s = [new Worker('x', 1), new Worker('y')]
            """.trimIndent()
            assertEquals(expected, result)
        }

    // FIXME(#2438): a class rename does not rename method or constructor parameter types
    //   (`RecursiveAstVisitor.visitParameter` does not track them, see #865), so `Worker(Service other)` below no
    //   longer compiles. The constructor name is renamed once, as #2434 requires; this test pins the rest.
    //   See: https://github.com/albertocavalcante/gvy/issues/2438
    @Test
    fun `renaming a class renames a constructor taking its own type but not the parameter type`(): Unit = runBlocking {
        val content = "class Service {\n    Service(Service other) { }\n    void take(Service s) { }\n}\n"

        val result = renameAndApply(content, Position(0, 7), "Worker")

        assertEquals("class Worker {\n    Worker(Service other) { }\n    void take(Service s) { }\n}\n", result)
    }

    @Test
    fun `renaming a nested class renames its constructors, static or inner`(): Unit = runBlocking {
        val content = """
            class Outer {
                static class Service {
                    Service(String name) { }
                }
                class Inner {
                    Inner(int size) { }
                }
                def make() { [new Service('a'), new Inner(1)] }
            }
        """.trimIndent()

        val nested = renameAndApply(content, Position(1, 18), "Worker")
        val inner = renameAndApply(content, Position(4, 12), "Part")

        assertEquals(content.replace("Service", "Worker"), nested)
        assertEquals(content.replace("Inner", "Part"), inner)
    }

    @Test
    fun `renaming an enum renames its constructor`(): Unit = runBlocking {
        val content = """
            enum Color {
                RED('r'), GREEN('g')
                final String code
                Color(String code) { this.code = code }
            }
            def c = Color.RED
        """.trimIndent()

        val result = renameAndApply(content, Position(0, 6), "Shade")

        assertEquals(content.replace("Color", "Shade"), result)
    }

    @Test
    fun `renaming a class leaves a method named like the class alone`(): Unit = runBlocking {
        val content = """
            class Service {
                Service(String name) { }
                String Service() { 'not a constructor' }
            }
            def s = new Service('a').Service()
        """.trimIndent()

        val result = renameAndApply(content, Position(0, 7), "Worker")

        val expected = """
            class Worker {
                Worker(String name) { }
                String Service() { 'not a constructor' }
            }
            def s = new Worker('a').Service()
        """.trimIndent()
        assertEquals(expected, result)
    }

    @Test
    fun `renaming a class leaves the constructors of a same-named nested class alone`(): Unit = runBlocking {
        val content = """
            class Service {
                Service(String name) { }
            }
            class Outer {
                static class Service {
                    Service(int size) { }
                }
                def make() { new Service(1) }
            }
            def top = new Service('a')
        """.trimIndent()

        val result = renameAndApply(content, Position(0, 7), "Worker")

        val expected = """
            class Worker {
                Worker(String name) { }
            }
            class Outer {
                static class Service {
                    Service(int size) { }
                }
                def make() { new Service(1) }
            }
            def top = new Worker('a')
        """.trimIndent()
        assertEquals(expected, result)
    }

    // FIXME(#2438): a class rename does not rename `extends Base` of a subclass in the same
    //   file (the visitor tracks the resolved superclass, which is the declaration itself), so the subclass below no
    //   longer compiles. This test pins that; the constructors, `this(...)` and `super(...)` are as #2434 requires.
    //   See: https://github.com/albertocavalcante/gvy/issues/2438
    @Test
    fun `renaming a class keeps this and super calls and renames its constructors`(): Unit = runBlocking {
        val content = """
            class Base {
                Base(String name) { }
                Base() { this('default') }
            }
            class Derived extends Base {
                Derived() { super('d') }
            }
        """.trimIndent()

        val result = renameAndApply(content, Position(0, 7), "Root")

        val expected = """
            class Root {
                Root(String name) { }
                Root() { this('default') }
            }
            class Derived extends Base {
                Derived() { super('d') }
            }
        """.trimIndent()
        assertEquals(expected, result)
    }

    // FIXME(#2438): `extends Point` stays, as in the test above.
    //   See: https://github.com/albertocavalcante/gvy/issues/2438
    @Test
    fun `renaming a class with generated constructors edits only the source`(): Unit = runBlocking {
        val content = """
            import groovy.transform.InheritConstructors
            import groovy.transform.TupleConstructor
            @TupleConstructor
            class Point {
                int x
            }
            @InheritConstructors
            class Spot extends Point { }
            def p = new Point(1)
            def s = new Spot(2)
        """.trimIndent()

        val point = renameAndApply(content, Position(3, 7), "Place")
        val spot = renameAndApply(content, Position(7, 7), "Mark")

        assertEquals(content.replace("class Point", "class Place").replace("new Point", "new Place"), point)
        assertEquals(content.replace("Spot", "Mark"), spot)
    }

    @Test
    fun `renaming a record renames its explicit constructors`(): Unit = runBlocking {
        val content = """
            record Pair(int a, int b) {
                Pair(int a) { this(a, a) }
            }
            def p = new Pair(1, 2)
            def q = new Pair(3)
        """.trimIndent()

        val result = renameAndApply(content, Position(0, 8), "Couple")

        assertEquals(content.replace("Pair", "Couple"), result)
    }

    @Test
    fun `renaming a record with a compact constructor is refused, as its name has no node to edit`(): Unit =
        runBlocking {
            val content = """
                record Interval(int from, int to) {
                    Interval {
                        assert from <= to
                    }
                }
                def r = new Interval(1, 2)
            """.trimIndent()

            val rename = runCatching { renameAndApply(content, Position(0, 8), "Span") }.exceptionOrNull()
            val prepare = prepareRename(content, Position(0, 8)).exceptionOrNull()

            val reason = "Cannot rename 'Interval': the name of its constructor at line 2 cannot be located exactly " +
                "in the source"
            for (failure in listOf(rename, prepare)) {
                val refused = assertInstanceOf(RenameRefusedException::class.java, failure)
                assertEquals(ResponseErrorCode.RequestFailed.value, refused.responseError.code)
                assertEquals(reason, refused.message)
            }
        }

    @Test
    fun `renaming a trait renames its uses`(): Unit = runBlocking {
        val content = """
            trait Greeter {
                String hi() { 'hi' }
            }
            class Person implements Greeter { }
        """.trimIndent()

        val result = renameAndApply(content, Position(0, 7), "Welcomer")

        assertEquals(content.replace("Greeter", "Welcomer"), result)
    }

    // FIXME(#2438): a class rename does not rename `new Service('a') { ... }` of an anonymous
    //   subclass (the call's type is the anonymous class), so the result below no longer compiles. This test pins
    //   that; the constructor is renamed as #2434 requires.
    //   See: https://github.com/albertocavalcante/gvy/issues/2438
    @Test
    fun `renaming a class renames its constructor but not an anonymous subclass's new`(): Unit = runBlocking {
        val content = """
            class Service {
                Service(String name) { }
                String run() { 'x' }
            }
            def s = new Service('a') { String run() { 'y' } }
        """.trimIndent()

        val result = renameAndApply(content, Position(0, 7), "Worker")

        val expected = """
            class Worker {
                Worker(String name) { }
                String run() { 'x' }
            }
            def s = new Service('a') { String run() { 'y' } }
        """.trimIndent()
        assertEquals(expected, result)
    }

    @Test
    fun `renaming a class in a buffer with a syntax error is refused`(): Unit = runBlocking {
        val content = "class Service {\n    Service(String name) { }\n    def broken( {\n}\n"

        val failure = runCatching { renameAndApply(content, Position(0, 7), "Worker") }.exceptionOrNull()

        assertTrue(failure is ResponseErrorException, "Got: $failure")
        assertEquals(
            "Document not compiled: " + workspaceRoot.resolve("Rename.groovy").toUri(),
            failure?.message,
        )
    }
}
