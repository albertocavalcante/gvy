// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import groovy.lang.GroovyClassLoader
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.InnerClassNode
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * Renames every class each corpus source declares, from its declaration, and pins the outcome of each: renamed, or
 * refused for a named reason (#2438, #2443). A renamed result must still compile with the Groovy compiler: necessary,
 * not sufficient, as a stale use that resolves to another class of the same name (`java.util.Date`) compiles too,
 * which the focused tests pin with exact text. A class that moves from renamed to refused, or from one reason to
 * another, fails here; so does any failure that is not a [RenameRefusedException] with `RequestFailed`.
 */
class ClassRenameCorpusTest {
    @TempDir
    lateinit var workspace: Path

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    fun `every class rename in the corpus has its pinned outcome`(fixture: Fixture) {
        assertEquals(null, compileError(fixture.source), "the corpus source ${fixture.name} must compile as written")
        val actual = declaredClasses(fixture.source).withIndex().associate { (index, type) ->
            val simpleName = type.nameWithoutPackage.substringAfterLast('$')
            val rename = SingleFileRename(Files.createDirectories(workspace.resolve("r$index")))
            val start = declarationName(fixture.source, type, simpleName)
            val result = runBlocking { rename.apply(fixture.source, start, "${simpleName}X") }
            type.name to outcome(fixture, simpleName, result)
        }

        assertEquals(fixture.expected, actual, "outcomes per class in ${fixture.name}")
    }

    private fun outcome(fixture: Fixture, simpleName: String, result: Result<String>): Outcome = result.fold(
        onSuccess = { renamed ->
            assertEquals(null, compileError(renamed), "renaming $simpleName in ${fixture.name} gave:\n$renamed")
            assertTrue(renamed != fixture.source, "renaming $simpleName in ${fixture.name} changed nothing")
            Outcome.Renamed
        },
        onFailure = { failure ->
            val refused = assertInstanceOf(
                RenameRefusedException::class.java,
                failure,
                "renaming $simpleName in ${fixture.name} threw $failure",
            )
            assertEquals(ResponseErrorCode.RequestFailed.value, refused.responseError.code, refused.message)
            val reason = Reason.of(refused.responseError.message)
            Outcome.Refused(
                checkNotNull(reason) { "renaming $simpleName in ${fixture.name}: no one reason: ${refused.message}" },
            )
        },
    )

    private fun declaredClasses(source: String): List<ClassNode> {
        val rename = SingleFileRename(Files.createDirectories(workspace.resolve("parse")))
        runBlocking { rename.open(source) }
        return rename.parsedClasses()
            .filter { it.lineNumber > 0 && !it.isScript && !(it is InnerClassNode && it.isAnonymous) }
    }

    /** The class's declared name: its first name token on or after the line its declaration starts. */
    private fun declarationName(source: String, type: ClassNode, name: String): Position {
        val index = TextIndex(source)
        val from = index.offsetOf(Position(type.lineNumber - 1, 0))
        val tokens = checkNotNull(GroovyTokenIndex.build(source).nameOccurrences(source, name))
        return index.positionOf(tokens.first { it.start >= from }.start)
    }

    /** The compile error of [source], or null; the unit's class loader is closed either way. */
    private fun compileError(source: String): String? = GroovyClassLoader().use { loader ->
        try {
            CompilationUnit(CompilerConfiguration(), null, loader)
                .apply { addSource("Rename.groovy", source) }
                .compile(Phases.CLASS_GENERATION)
            null
        } catch (e: CompilationFailedException) {
            e.message
        }
    }

    /** What renaming one class from its declaration does. */
    sealed interface Outcome {
        data object Renamed : Outcome

        data class Refused(val reason: Reason) : Outcome
    }

    /** The categories of refusal a class rename gives; each owns the messages [matches] recognises, and no other. */
    enum class Reason(private val matches: (String) -> Boolean) {
        UNREADABLE_TOKENS({ "cannot be read as complete tokens" in it }),
        EDIT_OUTSIDE_DOCUMENT({ "is outside the document being renamed" in it }),
        EDIT_NOT_ON_NAME({ "is not on the name in code" in it }),
        AMBIGUOUS_USE({ "cannot be located exactly in the source" in it }),
        CONTESTED_NAME({ "is spelled by uses of two different classes" in it }),
        NAME_CONFLICT({ "is both a use of the class and another name" in it }),
        UNCLASSIFIED_USE({ "could not be classified" in it }),
        TYPE_PARAMETER({ "renaming a type parameter is not supported" in it }),
        COMPACT_CONSTRUCTOR({ "renaming a record with a compact constructor" in it }),
        DECLARED_ELSEWHERE({ "it is not declared in this file" in it }),
        CROSS_FILE({ "renaming across files is not supported" in it && "it is not declared in this file" !in it }),
        ;

        companion object {
            /** The one reason [message] belongs to; null when none or more than one does. */
            fun of(message: String): Reason? = entries.filter { it.matches(message) }.singleOrNull()
        }
    }

    /** One corpus source and the outcome pinned for each class it declares, by binary name. */
    class Fixture(val name: String, source: String, val expected: Map<String, Outcome>) {
        val source: String = source.trimIndent()

        override fun toString(): String = name
    }

    companion object {
        @JvmStatic
        fun corpus(): List<Fixture> = CORPUS

        private val renamed = Outcome.Renamed

        private val CORPUS = listOf(
            Fixture(
                "nested generic type with a same-named JDK argument",
                """
                class Date {
                    static class Inner<T> {}
                    static Inner<java.util.Date> m(Inner<java.util.Date> p) {
                        def x = new Inner<java.util.Date>()
                        (Inner<java.util.Date>) x
                    }
                }
            """,
                mapOf(
                    "Date" to renamed,
                    "Date\$Inner" to renamed,
                ),
            ),
            Fixture(
                "nested generic type with a same-named nested argument",
                """
                class Node { static class Inner<T> {}; Inner<Tree.Node> f; Node.Inner<Node> g }
                class Tree { static class Node {}; static class Leaf {} }
            """,
                mapOf(
                    "Node" to renamed,
                    "Node\$Inner" to renamed,
                    "Tree" to renamed,
                    "Tree\$Node" to renamed,
                    "Tree\$Leaf" to renamed,
                ),
            ),
            Fixture(
                "same-named classes of other packages",
                """
                class Date { }
                class List { }
                Date mine = new Date()
                java.util.Date theirs = new java.util.Date()
                java.util.List<Date> dates = [mine]
                List local = new List()
            """,
                mapOf(
                    "Date" to renamed,
                    "List" to renamed,
                ),
            ),
            Fixture(
                "type parameters spelled like a class",
                """
                class T { }
                class Box<T> { T x; List<T> xs }
                class Pair<X extends T, Y> { X first; T second; Y third }
                class Util { def <T> T id(T value) { value } }
            """,
                mapOf(
                    "T" to renamed,
                    "Box" to renamed,
                    "Pair" to renamed,
                    "Util" to renamed,
                ),
            ),
            Fixture(
                "a type parameter T next to a class T: scoping",
                """
                class T { }
                class Box<T> { static class In { T s }; static T sm() { null }; class Member { T m } }
                class Shadow<T extends Comparable<T>> { T x; def <T> T id(T v) { v } }
                class Anon { def <T> Object make() { new Object() { T s } } }
                class Out { T z }
            """,
                mapOf(
                    "T" to renamed,
                    "Box" to renamed,
                    "Box\$In" to renamed,
                    "Box\$Member" to renamed,
                    "Shadow" to renamed,
                    "Anon" to renamed,
                    "Out" to renamed,
                ),
            ),
            Fixture(
                "a type parameter bounded by the class of the same name",
                """
                class Outer {
                    static class T { }
                    static class Box<T extends Outer.T> { T x; def k() { T.class }; def <T> void m() { T.class } }
                }
            """,
                mapOf(
                    "Outer" to renamed,
                    // `T.class` of the parameter spells the class's name and nothing proves it is not the class.
                    "Outer\$T" to Outcome.Refused(Reason.UNCLASSIFIED_USE),
                    "Outer\$Box" to renamed,
                ),
            ),
            Fixture(
                "nested classes",
                """
                class Outer {
                    static class Inner { Inner(int a) { } }
                    class Member { }
                    static class Service { Service(int a) { } }
                    Inner make() { new Inner(1) }
                }
                class Service { Service(String s) { } }
                Outer.Inner inner = new Outer.Inner(2)
                Outer.Service nested = new Outer.Service(3)
                Service top = new Service('a')
                List<Outer.Inner> inners = [inner]
            """,
                mapOf(
                    "Outer" to renamed,
                    "Outer\$Inner" to renamed,
                    "Outer\$Member" to renamed,
                    "Outer\$Service" to renamed,
                    "Service" to renamed,
                ),
            ),
            Fixture(
                "type uses in signatures and expressions",
                """
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
                    void take(Service q, Service... rest) { }
                }
                def anonymous = new Service() { }
                Service local = null
                def (Service first, Service second) = [null, null]
                def cast = (Service) null
                def coerced = null as Service
                def check = anonymous instanceof Service
                def constant = Service.NAME
                def literal = Service.class
                def reference = Service::new
                def closure = { Service x -> x }
                for (Service z in []) { }
                def made = new Service[2]
                def text = "${'$'}{Service.NAME}"
            """,
                mapOf(
                    "Api" to renamed,
                    "Service" to renamed,
                    "Derived" to renamed,
                    "Holder" to renamed,
                ),
            ),
            Fixture(
                "exceptions, records, enums, traits and annotations",
                """
                class Failure extends RuntimeException { }
                class Use {
                    void m() throws Failure {
                        try { } catch (Failure | IllegalStateException e) { }
                    }
                }
                record Holder(Failure failure) { }
                record Checked(int x) { Checked { assert x >= 0 } }
                enum Color { RED, GREEN { String toString() { 'g' } } }
                trait Greeter { String hi() { 'hi' } }
                class Person implements Greeter { Color color = Color.RED }
                @interface Marker { }
                @Marker class Marked { @Marker String field }
                sealed class Shape permits Square { }
                final class Square extends Shape { }
            """,
                mapOf(
                    "Failure" to renamed, "Use" to renamed, "Holder" to renamed,
                    // Not supported yet by design (ClassRenameConstructorsTest pins the message).
                    "Checked" to Outcome.Refused(Reason.COMPACT_CONSTRUCTOR),
                    "Color" to renamed, "Greeter" to renamed, "Person" to renamed, "Marker" to renamed,
                    "Marked" to renamed, "Shape" to renamed, "Square" to renamed,
                ),
            ),
        )
    }
}
