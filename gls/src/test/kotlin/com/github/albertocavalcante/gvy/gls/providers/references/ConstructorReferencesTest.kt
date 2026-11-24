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

/** Find references through the workspace index for constructors: declarations, overloads and the class itself. */
class ConstructorReferencesTest {
    @TempDir
    lateinit var workspace: Path

    private val declaration = "class A {\n    A(String s) { }\n    A(String s, int n) { }\n}\n"

    // Line 2 is `        [new A('x'), new A('x', 1), new A()]`: `A` of the three calls at columns 13, 25 and 40.
    private val caller = "class B {\n    def run() {\n        [new A('x'), new A('x', 1), new A()]\n    }\n}\n"

    private data class Fixture(val service: GroovyCompilationService, val declaration: Path, val caller: Path)

    private fun createWorkspace(declarationText: String = declaration, callerText: String = caller): Fixture {
        val sources = Files.createDirectories(workspace.resolve("src"))
        val declarationFile = sources.resolve("A.groovy")
        Files.writeString(declarationFile, declarationText)
        val callerFile = sources.resolve("B.groovy")
        Files.writeString(callerFile, callerText)
        val service = GroovyCompilationService(documentProvider = DocumentProvider())
        service.workspaceManager.initializeWorkspace(workspace)
        runBlocking { service.getWorkspaceCompiler().compileWorkspace() }
        return Fixture(service, declarationFile, callerFile)
    }

    private fun Fixture.references(file: Path, position: Position, includeDeclaration: Boolean = false) = runBlocking {
        ReferenceProvider(service, service.getWorkspaceSymbolIndex())
            .provideReferences(file.toUri().toString(), position, includeDeclaration)
            .toList()
    }

    private fun Fixture.at(line: Int, column: Int) =
        Location(caller.toUri().toString(), Range(Position(line, column), Position(line, column + 1)))

    @Test
    fun `references on a constructor declaration find the new expressions that call it`() {
        val fixture = createWorkspace()

        val references = fixture.references(fixture.declaration, Position(1, 4))

        assertEquals(listOf(fixture.at(2, 13)), references)
    }

    @Test
    fun `overloaded constructors are told apart by the arguments of the call`() {
        val fixture = createWorkspace()

        val references = fixture.references(fixture.declaration, Position(2, 4))

        assertEquals(listOf(fixture.at(2, 25)), references)
    }

    @Test
    fun `references on a constructor can include its declaration`() {
        val fixture = createWorkspace()

        val references = fixture.references(fixture.declaration, Position(1, 4), includeDeclaration = true)

        assertEquals(
            setOf(
                fixture.at(2, 13),
                Location(fixture.declaration.toUri().toString(), Range(Position(1, 4), Position(1, 5))),
            ),
            references.toSet(),
        )
        assertEquals(references.size, references.toSet().size, "no location is reported twice")
    }

    @Test
    fun `references on the class still find every new expression, constructor calls included`() {
        val fixture = createWorkspace()

        val references = fixture.references(fixture.declaration, Position(0, 6))

        assertEquals(setOf(fixture.at(2, 13), fixture.at(2, 25), fixture.at(2, 40)), references.toSet())
        assertEquals(3, references.size, "each new expression is reported once")
    }

    @Test
    fun `a call that no constructor can be chosen for still counts as a reference to the class only`() {
        val fixture = createWorkspace()

        val ofConstructors = listOf(Position(1, 4), Position(2, 4)).flatMap {
            fixture.references(fixture.declaration, it)
        }

        assertEquals(false, fixture.at(2, 40) in ofConstructors)
    }
}
