// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.groovyparser.tokens.GroovyTokenIndex
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.InnerClassNode
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * Renames every class each corpus source declares, from its declaration, and compiles every result with the Groovy
 * compiler: a rename either refuses or yields changed source that still compiles (#2438). Compiling is necessary,
 * not sufficient: a stale use that resolves to another class of the same name (`java.util.Date`) compiles too, which
 * the focused tests pin with exact text.
 */
class ClassRenameCorpusTest {
    @TempDir
    lateinit var workspace: Path

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    fun `every class rename in the corpus refuses or still compiles`(name: String, source: String) {
        assertEquals(null, compileError(source), "the corpus source $name must compile as written")
        val outcomes = declaredClasses(source).mapIndexed { index, type ->
            val simpleName = type.nameWithoutPackage.substringAfterLast('$')
            val rename = SingleFileRename(Files.createDirectories(workspace.resolve("r$index")))
            val start = declarationName(source, type, simpleName)
            val result = runBlocking { rename.apply(source, start, "${simpleName}X") }
            result.fold(
                onSuccess = { renamed ->
                    assertEquals(null, compileError(renamed), "renaming $simpleName in $name gave:\n$renamed")
                    assertTrue(renamed != source, "renaming $simpleName in $name changed nothing")
                    "$simpleName: renamed"
                },
                onFailure = { failure ->
                    assertTrue(failure is ResponseErrorException, "renaming $simpleName in $name threw $failure")
                    "$simpleName: refused (${failure.message})"
                },
            )
        }
        assertTrue(outcomes.any { it.endsWith("renamed") }, "no rename succeeded in $name: $outcomes")
    }

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

    private fun compileError(source: String): String? = try {
        CompilationUnit().apply { addSource("Rename.groovy", source) }.compile(Phases.CLASS_GENERATION)
        null
    } catch (e: CompilationFailedException) {
        e.message
    }

    companion object {
        @JvmStatic
        fun corpus(): List<Array<Any>> = CORPUS.map { (name, source) -> arrayOf(name, source.trimIndent()) }

        private val CORPUS = listOf(
            "nested generic type with a same-named JDK argument" to """
                class Date {
                    static class Inner<T> {}
                    static Inner<java.util.Date> m(Inner<java.util.Date> p) {
                        def x = new Inner<java.util.Date>()
                        (Inner<java.util.Date>) x
                    }
                }
            """,
            "nested generic type with a same-named nested argument" to """
                class Node { static class Inner<T> {}; Inner<Tree.Node> f; Node.Inner<Node> g }
                class Tree { static class Node {}; static class Leaf {} }
            """,
            "same-named classes of other packages" to """
                class Date { }
                class List { }
                Date mine = new Date()
                java.util.Date theirs = new java.util.Date()
                java.util.List<Date> dates = [mine]
                List local = new List()
            """,
            "nested classes" to """
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
            "type uses in signatures and expressions" to """
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
            "exceptions, records, enums, traits and annotations" to """
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
        )
    }
}
