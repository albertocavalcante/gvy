// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.source.DocumentVersion
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * A class with no source in the workspace is refused as a dependency without scanning the workspace for its uses; a
 * class another workspace file declares keeps the refusal that names that file (#2443).
 */
class ClassRenameDependencyTest {
    @TempDir
    lateinit var tempDir: Path

    private val documents = DocumentProvider()
    private val service = GroovyCompilationService(documentProvider = documents)

    /** The one seam that shows a cross-file scan ran: the number of workspace walks it made. */
    private val walks = WorkspaceWalkCache(clock = { System.currentTimeMillis() + 60_000 })
    private val provider by lazy { RenameProvider(service, documents, walks = walks) }

    private fun workspace(files: Map<String, String>, open: String): URI = runBlocking {
        val src = Files.createDirectories(tempDir.resolve("src"))
        val uris = files.mapValues { (name, text) -> src.resolve(name).also { Files.writeString(it, text) }.toUri() }
        service.workspaceManager.initializeWorkspace(tempDir)
        service.getWorkspaceCompiler().compileWorkspace()
        val uri = checkNotNull(uris[open])
        documents.put(uri, files.getValue(open), DocumentVersion.of(1))
        service.compile(uri, files.getValue(open))
        uri
    }

    private fun positionOf(text: String, needle: String): Position {
        val index = text.indexOf(needle).also { check(it >= 0) { needle } }
        val before = text.substring(0, index)
        return Position(before.count { it == '\n' }, index - (before.lastIndexOf('\n') + 1))
    }

    private val greeter =
        "class Greeter {\n    String name\n    def k() { String.valueOf(1) }\n    Map.Entry entry\n}\n"
    private val mentioningString = "class Other {\n    String other\n}\n"

    /** Rename and prepareRename at [needle] (+[offset]) both refuse as a dependency, without a workspace scan. */
    private fun assertDependency(needle: String, offset: Int, where: String) {
        val uri = workspace(mapOf("Greeter.groovy" to greeter, "Other.groovy" to mentioningString), "Greeter.groovy")
        val position = positionOf(greeter, needle).let { Position(it.line, it.character + offset) }
        val rename = runCatching { runBlocking { provider.provideRename(uri.toString(), position, "Text") } }
        val prepare = runCatching { runBlocking { provider.providePrepareRename(uri.toString(), position) } }

        for ((what, failure) in listOf("rename" to rename.exceptionOrNull(), "prepare" to prepare.exceptionOrNull())) {
            val refused = assertInstanceOf(RenameRefusedException::class.java, failure, "$what at $needle: $failure")
            assertEquals(ResponseErrorCode.InvalidRequest.value, refused.responseError.code, what)
            assertEquals(
                "Cannot rename symbols defined in dependencies (read-only): $where",
                refused.responseError.message,
                what,
            )
        }
        assertEquals(0, walks.walks, "the workspace must not be scanned for uses of a dependency symbol")
    }

    @Test
    fun `a JDK type used as a type is refused as a dependency without scanning the workspace`(): Unit =
        assertDependency("String name", 0, "java.lang.String")

    @Test
    fun `a JDK class named by a static call is refused the same way`(): Unit =
        assertDependency("String.valueOf", 0, "java.lang.String")

    @Test
    fun `naming a dependency class never loads it into the language server`() {
        // A resolved dependency node would load its class through getTypeClass: a jar class with a missing
        // transitive dependency throws an Error there, and project code must never be loaded for this.
        val dependency = object : ClassNode(Map.Entry::class.java) {
            override fun getTypeClass(): Class<*> = throw AssertionError("the dependency class was loaded")
        }

        assertEquals("java.util.Map\$Entry", ClassRenameUses.dependencyName(dependency))
    }

    @Test
    fun `a dependency class is named as the compiler names it, whatever its name contains`() {
        assertEquals("java.util.Map\$Entry", ClassRenameUses.dependencyName(ClassHelper.make(Map.Entry::class.java)))
        assertEquals("pkg.Dollar\$Named", ClassRenameUses.dependencyName(ClassHelper.make("pkg.Dollar\$Named")))
    }

    @Test
    fun `a nested JDK type is refused as a dependency and named by its binary name`(): Unit =
        assertDependency("Map.Entry", "Map.".length, "java.util.Map\$Entry")

    @Test
    fun `a class another workspace file declares keeps the refusal that names the file`() {
        val a = "class A {\n    def greet() { 'hi' }\n}\n"
        val b = "class B {\n    A field\n}\n"
        val uri = workspace(mapOf("A.groovy" to a, "B.groovy" to b), "B.groovy")
        val position = positionOf(b, "A field")

        val failure = runCatching { runBlocking { provider.provideRename(uri.toString(), position, "Z") } }
            .exceptionOrNull()

        val refused = assertInstanceOf(RenameRefusedException::class.java, failure, "rename: $failure")
        assertEquals(ResponseErrorCode.RequestFailed.value, refused.responseError.code)
        assertTrue(
            "'A'" in refused.responseError.message && "A.groovy" in refused.responseError.message,
            refused.message,
        )
        assertTrue(walks.walks > 0, "the cross-file scan is the check that names the other file")
    }
}
