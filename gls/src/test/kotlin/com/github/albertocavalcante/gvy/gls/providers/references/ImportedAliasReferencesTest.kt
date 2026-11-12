// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.providers.rename.RenameProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportedAliasReferencesTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `same-file references preserve the imported constructor alias spelling`(): Unit = runBlocking {
        val file = workspace.resolve("AliasCases.groovy")
        val source = "import Foo as Alias\nclass Foo {}\ndef a = new Foo()\ndef b = new Alias(Foo.class)\n"
        Files.writeString(file, source)
        val service = GroovyCompilationService()
        try {
            service.compile(file.toUri(), source)
            val parsed = assertNotNull(service.getParseResult(file.toUri()))
            assertTrue(parsed.outcome.achievedPhase >= Phases.SEMANTIC_ANALYSIS)
            val index = TextIndex(source)
            val declaration = source.indexOf("class Foo") + "class ".length
            for (includeDeclaration in listOf(true, false)) {
                val spans = listOf(
                    source.indexOf("Foo") to "Foo",
                    source.indexOf("new Foo") + 4 to "Foo",
                    source.lastIndexOf("Foo") to "Foo",
                    source.lastIndexOf("Alias") to "Alias",
                ) +
                    if (includeDeclaration) listOf(declaration to "Foo") else emptyList()
                val expected = spans.map { (offset, name) ->
                    Location(
                        file.toUri().toString(),
                        Range(index.positionOf(offset), index.positionOf(offset + name.length)),
                    )
                }
                val actual = ReferenceProvider(service).provideReferences(
                    file.toUri().toString(),
                    index.positionOf(declaration + 1),
                    includeDeclaration,
                ).toList()
                assertEquals(expected.sortedLocations(), actual.sortedLocations())
            }
        } finally {
            service.clearCaches()
        }
    }

    @Test
    fun `unopened indexed aliases retain resolved qualified class ownership`(): Unit = runBlocking {
        fixture().use { fixture ->
            fixture.service.getWorkspaceCompiler().compileWorkspace()
            assertNull(fixture.service.getCompiledContent(fixture.caller.toUri()))
            fixture.assertReferences()
        }
    }

    @Test
    fun `evicting the caller AST preserves indexed alias references`(): Unit = runBlocking {
        fixture(cacheLimit = 1).use { fixture ->
            fixture.service.getWorkspaceCompiler().compileWorkspace()
            fixture.service.compile(fixture.caller.toUri(), fixture.sources.getValue(fixture.caller))
            assertNotNull(fixture.service.getCompiledContent(fixture.caller.toUri()))
            fixture.service.compile(fixture.declaration.toUri(), fixture.sources.getValue(fixture.declaration))
            fixture.service.compile(fixture.beta.toUri(), fixture.sources.getValue(fixture.beta))
            assertNull(fixture.service.getCompiledContent(fixture.caller.toUri()))

            fixture.assertReferences()
        }
    }

    @Test
    fun `unopened alias ranges retain UTF16 positions after supplementary characters`(): Unit = runBlocking {
        fixture(prefix = "/* ${"😀".repeat(12)} */ ").use { fixture ->
            fixture.service.getWorkspaceCompiler().compileWorkspace()
            assertNull(fixture.service.getCompiledContent(fixture.caller.toUri()))
            fixture.assertReferences()
        }
    }

    @Test
    fun `indexed constructor arguments and class literals retain their own alias identities`(): Unit = runBlocking {
        fixture(argumentCollision = true).use { fixture ->
            fixture.service.getWorkspaceCompiler().compileWorkspace()
            fixture.assertReferences()
        }
    }

    @Test
    fun `generic arguments cannot hide the aliased constructor head`(): Unit = runBlocking {
        fixture(genericCollision = true).use { fixture ->
            fixture.service.getWorkspaceCompiler().compileWorkspace()
            fixture.assertReferences()
        }
    }

    @Test
    fun `nested class aliases preserve source declarations and class literal spellings`(): Unit = runBlocking {
        val file = workspace.resolve("NestedAliases.groovy")
        val source = "package p\nimport p.Outer.Inner as Nested\n" +
            "class Outer { static class Inner {} }\ndef a = new Nested()\ndef b = Nested.class\n"
        Files.writeString(file, source)
        val service = GroovyCompilationService()
        try {
            service.compile(file.toUri(), source)
            assertTrue(
                assertNotNull(service.getParseResult(file.toUri())).outcome.achievedPhase >= Phases.SEMANTIC_ANALYSIS,
            )
            val index = TextIndex(source)
            val declaration = source.indexOf("class Inner") + "class ".length
            val uses = listOf(
                source.indexOf(".Inner") + 1 to "Inner",
                source.indexOf("new Nested") + 4 to "Nested",
                source.lastIndexOf("Nested") to "Nested",
            )
            for (includeDeclaration in listOf(true, false)) {
                val expected = (uses + if (includeDeclaration) listOf(declaration to "Inner") else emptyList())
                    .map { (offset, name) ->
                        Location(
                            file.toUri().toString(),
                            Range(index.positionOf(offset), index.positionOf(offset + name.length)),
                        )
                    }
                val actual = ReferenceProvider(service).provideReferences(
                    file.toUri().toString(),
                    index.positionOf(declaration + 1),
                    includeDeclaration,
                ).toList()
                assertEquals(
                    expected.sortedLocations(),
                    actual.sortedLocations(),
                    "includeDeclaration=$includeDeclaration",
                )
            }
        } finally {
            service.clearCaches()
        }
    }

    @Test
    fun `canonical class rename rejects alias heads without changing source`(): Unit = runBlocking {
        val file = workspace.resolve("AliasRename.groovy")
        val source = "import Foo as Alias\nclass Foo {}\ndef b = new Alias(Foo.class)\n"
        Files.writeString(file, source)
        val documents = DocumentProvider().apply { put(file.toUri(), source) }
        val service = GroovyCompilationService()
        try {
            service.workspaceManager.initializeWorkspace(workspace)
            service.compile(file.toUri(), source)
            assertTrue(
                assertNotNull(service.getParseResult(file.toUri())).outcome.achievedPhase >= Phases.SEMANTIC_ANALYSIS,
            )
            val index = TextIndex(source)
            val error = assertFailsWith<org.eclipse.lsp4j.jsonrpc.ResponseErrorException> {
                RenameProvider(service) { documents.get(it) }.provideRename(
                    file.toUri().toString(),
                    index.positionOf(source.indexOf("class Foo") + 7),
                    "Renamed",
                )
            }
            assertEquals(
                org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.ContentModified.value,
                error.responseError.code,
            )
            assertEquals(source, Files.readString(file))
            assertEquals(source, documents.get(file.toUri()))
        } finally {
            service.clearCaches()
        }
    }

    @Test
    fun `explicit same-spelling aliases cannot authorize canonical class rename`(): Unit = runBlocking {
        val file = workspace.resolve("IdentityAliasRename.groovy")
        val source = "import Foo as Foo\nclass Foo {}\ndef b = new Foo()\n"
        Files.writeString(file, source)
        val documents = DocumentProvider().apply { put(file.toUri(), source) }
        val service = GroovyCompilationService()
        try {
            service.workspaceManager.initializeWorkspace(workspace)
            service.compile(file.toUri(), source)
            assertTrue(
                assertNotNull(service.getParseResult(file.toUri())).outcome.achievedPhase >= Phases.SEMANTIC_ANALYSIS,
            )
            val index = TextIndex(source)
            val error = assertFailsWith<org.eclipse.lsp4j.jsonrpc.ResponseErrorException> {
                RenameProvider(service) { documents.get(it) }.provideRename(
                    file.toUri().toString(),
                    index.positionOf(source.indexOf("class Foo") + 7),
                    "Renamed",
                )
            }
            assertEquals(
                org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.ContentModified.value,
                error.responseError.code,
            )
            assertEquals(source, Files.readString(file))
            assertEquals(source, documents.get(file.toUri()))
        } finally {
            service.clearCaches()
        }
    }

    private fun fixture(
        prefix: String = "",
        cacheLimit: Int = 300,
        argumentCollision: Boolean = false,
        genericCollision: Boolean = false,
    ): Fixture {
        val root = Files.createDirectories(workspace.resolve("src"))
        val declaration = root.resolve("alpha/Foo.groovy")
        val beta = root.resolve("beta/Foo.groovy")
        val caller = root.resolve("use/Consumer.groovy")
        val calls = when {
            argumentCollision -> "new Left(Right.class); new Right(Left.class); new Left()"
            genericCollision -> "new Left<alpha.Foo>(); new Right(); new Left()"
            else -> "new Left(); new Right(); new Left()"
        }
        val sources = mapOf(
            declaration to "package alpha\nclass Foo<T> {}\n",
            beta to "package beta\nclass Foo {}\n",
            caller to "package use\nimport alpha.Foo as Left\nimport beta.Foo as Right\nclass Consumer {\n" +
                "    def make() { $prefix$calls }\n}\n",
        )
        sources.forEach { (file, source) ->
            Files.createDirectories(file.parent)
            Files.writeString(file, source)
        }
        val service = GroovyCompilationService(compilationCacheMaxEntries = cacheLimit)
        service.workspaceManager.initializeWorkspace(workspace)
        service.workspaceManager.updateWorkspaceModel(workspace, emptyList(), listOf(root))
        return Fixture(service, declaration, beta, caller, sources)
    }

    private data class Fixture(
        val service: GroovyCompilationService,
        val declaration: Path,
        val beta: Path,
        val caller: Path,
        val sources: Map<Path, String>,
    ) : AutoCloseable {
        suspend fun assertReferences() {
            val index = assertNotNull(service.getWorkspaceSymbolIndex())
            val document = assertNotNull(index.getDocument(caller.toUri()))
            assertTrue(
                document.occurrences.count { it.symbol == "alpha/Foo#" } >= 3,
                "The real compiler must bind the import and both Left constructors to alpha.Foo",
            )
            assertTrue(
                document.occurrences.count { it.symbol == "beta/Foo#" } >= 2,
                "The same simple class name must also resolve independently to beta.Foo",
            )
            val origin = sources.getValue(declaration)
            val originIndex = TextIndex(origin)
            val text = sources.getValue(caller)
            val textIndex = TextIndex(text)
            val importOffset = text.indexOf("alpha.Foo") + "alpha.".length
            val aliases = Regex("\\bLeft\\b").findAll(text).drop(1).map { it.range.first }.toList()
            assertTrue(aliases.size >= 2)
            for (includeDeclaration in listOf(true, false)) {
                val expected = listOf(importOffset to "Foo").plus(aliases.map { it to "Left" }).map { (offset, name) ->
                    Location(
                        caller.toUri().toString(),
                        Range(textIndex.positionOf(offset), textIndex.positionOf(offset + name.length)),
                    )
                }.toMutableList()
                if (includeDeclaration) {
                    val offset = origin.indexOf("Foo")
                    expected.add(
                        Location(
                            declaration.toUri().toString(),
                            Range(originIndex.positionOf(offset), originIndex.positionOf(offset + "Foo".length)),
                        ),
                    )
                }
                val queries = listOf(
                    declaration.toUri().toString() to originIndex.positionOf(origin.indexOf("Foo") + 1),
                    caller.toUri().toString() to textIndex.positionOf(aliases.first() + 1),
                    caller.toUri().toString() to textIndex.positionOf(text.indexOf("as Left") + 4),
                )
                queries.forEach { (uri, position) ->
                    val actual = ReferenceProvider(
                        service,
                        index,
                    ).provideReferences(uri, position, includeDeclaration).toList()
                    assertEquals(
                        expected.sortedLocations(),
                        actual.sortedLocations(),
                        "includeDeclaration=$includeDeclaration at $uri:$position",
                    )
                }
            }
        }

        override fun close() = service.clearCaches()
    }
}

private fun List<Location>.sortedLocations(): List<Location> =
    sortedWith(compareBy({ it.uri }, { it.range.start.line }, { it.range.start.character }))
