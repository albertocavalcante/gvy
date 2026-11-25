// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.rename.RenameProvider
import com.github.albertocavalcante.gvy.gls.providers.rename.RenameRefusedException
import com.github.albertocavalcante.gvy.gls.providers.rename.applyTextEdits
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.source.DocumentVersion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * With a workspace index, the uses of a type parameter spelled like a class (`T.class`, `T.foo()`, `new T()`,
 * `x instanceof T` in `Box<T extends pkg.T>`) are still not uses of the class: neither find-references nor rename may
 * take them from the index (#2443 review, bot finding).
 */
class TypeParameterIndexedUsesTest {
    @TempDir
    lateinit var workspace: Path

    private val documents = DocumentProvider()
    private val service = GroovyCompilationService(documentProvider = documents)

    private val box = "package pkg\nclass T {}\nclass Box<T extends pkg.T> {\n" +
        "    def a() { T.class }\n    def b() { T.foo() }\n    def c() { new T() }\n    def d(x) { x instanceof T }\n" +
        "    def e(x) { (T) x }\n}\n"

    private fun indexed(files: Map<String, String>): Map<String, Path> {
        val src = Files.createDirectories(workspace.resolve("src"))
        val paths = files.mapValues { (name, text) -> src.resolve(name).also { Files.writeString(it, text) } }
        service.workspaceManager.initializeWorkspace(workspace)
        runBlocking { service.getWorkspaceCompiler().compileWorkspace() }
        return paths
    }

    private fun referencesOfClassT(file: Path): List<String> = runBlocking {
        ReferenceProvider(service, service.getWorkspaceSymbolIndex())
            .provideReferences(file.toUri().toString(), Position(1, 6), includeDeclaration = true)
            .toList()
            .map { "${it.uri.substringAfterLast('/')}:${it.range.start.line}:${it.range.start.character}" }
            .sorted()
    }

    @Test
    fun `find-references with the index lists the class declaration only, not the parameter's uses`() {
        val paths = indexed(mapOf("Box.groovy" to box))

        // FIXME(#2459): the qualified bound `pkg.T` (line 2, column 24) is a use too, which the scan does not report.
        assertEquals(listOf("Box.groovy:1:6"), referencesOfClassT(paths.getValue("Box.groovy")))
    }

    @Test
    fun `find-references with a second file that uses the class lists that use and not the parameter's`() {
        val paths = indexed(
            mapOf(
                "Box.groovy" to box,
                "User.groovy" to "package pkg\nclass User { def make() { new T() } }\n",
            ),
        )

        assertEquals(
            listOf("Box.groovy:1:6", "User.groovy:1:30"),
            referencesOfClassT(paths.getValue("Box.groovy")),
        )
    }

    private fun renameClassT(files: Map<String, String>): Result<String> = runBlocking {
        val paths = indexed(files)
        val file = paths.getValue("Box.groovy")
        val uri = file.toUri()
        documents.put(uri, files.getValue("Box.groovy"), DocumentVersion.of(1))
        service.compile(uri, files.getValue("Box.groovy"))
        runCatching {
            val edits = RenameProvider(service, documents)
                .provideRename(uri.toString(), Position(1, 6), "Tee")
                .documentChanges.filter { it.isLeft && it.left.textDocument.uri == uri.toString() }
                .flatMap { it.left.edits }
            applyTextEdits(files.getValue("Box.groovy"), edits)
        }
    }

    @Test
    fun `rename with the index present refuses the class literal and call receiver of the parameter`() {
        val result = renameClassT(mapOf("Box.groovy" to box))

        val refused = assertInstanceOf(RenameRefusedException::class.java, result.exceptionOrNull(), "$result")
        assertEquals(ResponseErrorCode.RequestFailed.value, refused.responseError.code)
        assertEquals("Cannot rename 'T': a use at line 4 could not be classified", refused.responseError.message)
    }

    @Test
    fun `references on an indexed type parameter constructor never fall back to the enclosing method`() = runBlocking {
        val text = "package pkg\nclass T {}\n" +
            "class Box<T extends pkg.T> { def m() { new T() } }\n" +
            "class Real { def m() { new T() } }\n"
        val file = indexed(mapOf("Box.groovy" to text)).getValue("Box.groovy")
        val callColumn = text.lines()[2].indexOf("new T")
        for (column in listOf(callColumn, callColumn + "new ".length)) {
            assertEquals(
                emptyList<Location>(),
                ReferenceProvider(service, service.getWorkspaceSymbolIndex())
                    .provideReferences(
                        file.toUri().toString(),
                        Position(2, column),
                        includeDeclaration = true,
                    ).toList(),
            )
        }
    }

    @Test
    fun `an indexed type parameter constructor does not hide references inside its arguments`() = runBlocking {
        val text = "package pkg\nclass T {}\n" +
            "class Box<T extends pkg.T> { def m() { new T(new Real()) } }\nclass Real {}\n"
        val file = indexed(mapOf("Box.groovy" to text)).getValue("Box.groovy")
        val column = text.lines()[2].indexOf("Real")

        val references = ReferenceProvider(service, service.getWorkspaceSymbolIndex())
            .provideReferences(file.toUri().toString(), Position(2, column), includeDeclaration = true)
            .toList().map { it.range.start.line to it.range.start.character }.sortedBy { it.first }

        assertEquals(listOf(2 to column, 3 to 6), references)
    }

    @Test
    fun `an indexed method name equal to its return type still finds its callers`() = runBlocking {
        val text = "class Kind {}\nclass Factory { Kind Kind() { null } }\n" +
            "class Caller { def m() { new Factory().Kind() } }\n"
        val file = indexed(mapOf("Factory.groovy" to text)).getValue("Factory.groovy")
        val methodColumn = text.lines()[1].lastIndexOf("Kind")
        val callColumn = text.lines()[2].indexOf("Kind")

        val references = ReferenceProvider(service, service.getWorkspaceSymbolIndex())
            .provideReferences(file.toUri().toString(), Position(1, methodColumn), includeDeclaration = false)
            .toList().map { it.range.start.line to it.range.start.character }

        assertEquals(listOf(2 to callColumn), references)
    }

    @Test
    fun `rename with the index present edits the constructor call, instanceof and cast forms only on the class`() {
        val body = "package pkg\nclass T {}\nclass Box<T extends pkg.T> {\n" +
            "    def c() { new T() }\n    def d(x) { x instanceof T }\n    def e(x) { (T) x }\n}\n"

        val result = renameClassT(mapOf("Box.groovy" to body))

        assertEquals(body.replace("class T {}", "class Tee {}").replace("pkg.T>", "pkg.Tee>"), result.getOrThrow())
    }

    @Test
    fun `rename with a second file in the workspace is decided by the cross-file guard`() {
        val body = "package pkg\nclass T {}\nclass Box<T extends pkg.T> { def c() { new T() } }\n"

        val result = renameClassT(
            mapOf(
                "Box.groovy" to body,
                "User.groovy" to "package pkg\nclass User { def make() { new T() } }\n",
            ),
        )

        assertEquals(
            "'T' is used in 1 other file (User.groovy); renaming across files is not supported yet",
            result.exceptionOrNull()?.message,
        )
    }
}
