// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceReferenceCoverageTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `references include unopened callers after only a workspace compile`(): Unit = runBlocking {
        val (service, declaration, caller) = createWorkspace()
        service.getWorkspaceCompiler().compileWorkspace()
        val index = assertNotNull(service.getWorkspaceSymbolIndex())
        assertNotNull(index.getDocument(caller.toUri()), "the unopened caller is semantically indexed")
        assertNull(service.getCompiledContent(caller.toUri()), "no per-file compile has populated the AST cache")

        val references = ReferenceProvider(service, index)
            .provideReferences(declaration.toUri().toString(), Position(1, 12), includeDeclaration = false)
            .toList()

        assertEquals(listOf(callerLocation(caller)), references)
    }

    @Test
    fun `indexed method calls select the member when receiver has the same name`(): Unit = runBlocking {
        val (service, declaration, caller) = createWorkspace()
        Files.writeString(declaration, "class A {\n    String greet(A value) { 'hi' }\n}\n")
        Files.writeString(caller, "class B {\n    String run(A greet) {\n        greet.greet(greet)\n    }\n}\n")
        service.getWorkspaceCompiler().compileWorkspace()

        val references = ReferenceProvider(service, service.getWorkspaceSymbolIndex())
            .provideReferences(declaration.toUri().toString(), Position(1, 12), includeDeclaration = false)
            .toList()

        assertEquals(
            listOf(Location(caller.toUri().toString(), Range(Position(2, 14), Position(2, 19)))),
            references,
        )
    }

    @Test
    fun `indexed properties select the member when receiver has the same name`(): Unit = runBlocking {
        val (service, declaration, caller) = createWorkspace()
        Files.writeString(declaration, "class A {\n    String greet\n}\n")
        Files.writeString(caller, "class B {\n    String run(A greet) {\n        greet.greet\n    }\n}\n")
        service.getWorkspaceCompiler().compileWorkspace()

        val references = ReferenceProvider(service, service.getWorkspaceSymbolIndex())
            .provideReferences(declaration.toUri().toString(), Position(1, 12), includeDeclaration = false)
            .toList()

        assertEquals(
            listOf(Location(caller.toUri().toString(), Range(Position(2, 14), Position(2, 19)))),
            references,
        )
    }

    @Test
    fun `evicting per-file ASTs does not lose workspace references`(): Unit = runBlocking {
        val (service, declaration, caller) = createWorkspace(cacheLimit = 1)
        service.compile(declaration.toUri(), Files.readString(declaration))
        service.compile(caller.toUri(), Files.readString(caller))
        val other = workspace.resolve("src/Other.groovy")
        Files.writeString(other, "class Other {}")
        service.compile(other.toUri(), Files.readString(other))
        assertNull(service.getCompiledContent(caller.toUri()), "the caller AST has been evicted")

        val references = ReferenceProvider(service, service.getWorkspaceSymbolIndex())
            .provideReferences(declaration.toUri().toString(), Position(1, 12), includeDeclaration = false)
            .toList()

        assertEquals(listOf(callerLocation(caller)), references)
    }

    @Test
    fun `reference ranges follow the open buffer rather than the saved file`(): Unit = runBlocking {
        val fixture = createWorkspace()
        val buffer = "class B {\n    String run() {\n\n        // unsaved change\n        new A().greet()\n    }\n}\n"
        fixture.documents.put(fixture.caller.toUri(), buffer)
        fixture.service.compile(fixture.caller.toUri(), buffer)
        fixture.service.getWorkspaceCompiler().compileWorkspace()

        val references = ReferenceProvider(fixture.service, fixture.service.getWorkspaceSymbolIndex())
            .provideReferences(fixture.declaration.toUri().toString(), Position(1, 12), includeDeclaration = false)
            .toList()

        assertEquals(
            listOf(Location(fixture.caller.toUri().toString(), Range(Position(4, 16), Position(4, 21)))),
            references,
        )
    }

    @Test
    fun `metadata without source text is never interpreted using a separate cache entry`(): Unit = runBlocking {
        val (service, declaration, caller) = createWorkspace()
        service.compile(caller.toUri(), Files.readString(caller))
        service.getWorkspaceCompiler().compileWorkspace()
        val index = assertNotNull(service.getWorkspaceSymbolIndex())
        val document = assertNotNull(index.getDocument(caller.toUri()))
        assertTrue(document.occurrences.isNotEmpty())
        assertNotNull(service.getCompiledContent(caller.toUri()), "an unrelated text cache is available")
        index.updateDocument(caller.toUri(), document)

        val references = ReferenceProvider(service, index)
            .provideReferences(declaration.toUri().toString(), Position(1, 12), includeDeclaration = false)
            .toList()

        assertTrue(references.isEmpty(), "metadata-only updates cannot supply identifier locations")
    }

    private data class Fixture(
        val service: GroovyCompilationService,
        val declaration: Path,
        val caller: Path,
        val documents: DocumentProvider,
    )

    private fun createWorkspace(cacheLimit: Int = 300): Fixture {
        val sources = Files.createDirectories(workspace.resolve("src"))
        val declaration = sources.resolve("A.groovy")
        Files.writeString(declaration, "class A {\n    String greet() { 'hi' }\n}\n")
        val caller = sources.resolve("B.groovy")
        Files.writeString(caller, "class B {\n    String run() {\n        new A().greet()\n    }\n}\n")
        val documents = DocumentProvider()
        val service = GroovyCompilationService(documentProvider = documents, compilationCacheMaxEntries = cacheLimit)
        service.workspaceManager.initializeWorkspace(workspace)
        return Fixture(service, declaration, caller, documents)
    }

    private fun callerLocation(caller: Path): Location =
        Location(caller.toUri().toString(), Range(Position(2, 16), Position(2, 21)))
}
