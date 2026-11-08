package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportedNestedAliasReferencesTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `nested and dollar names keep source spellings with and without cached ASTs`(): Unit = runBlocking {
        val fixture = fixture()
        val (outer, dollar, caller) = fixture
        val sources = fixture.sources
        val service = GroovyCompilationService()
        try {
            service.workspaceManager.initializeWorkspace(workspace)
            service.workspaceManager.updateWorkspaceModel(workspace, emptyList(), listOf(caller.parent.parent))
            service.getWorkspaceCompiler().compileWorkspace()
            val index = assertNotNull(service.getWorkspaceSymbolIndex())
            assertNull(service.getCompiledContent(caller.toUri()))
            for (cached in listOf(false, true)) {
                if (cached) compileSources(service, sources)
                for ((file, name, alias) in listOf(
                    Triple(outer, "Inner", "Nested"),
                    Triple(dollar, "Dollar\$Part", "Money"),
                )) {
                    val declarationSource = sources.getValue(file)
                    val text = sources.getValue(caller)
                    val declaration = declarationSource.indexOf("class $name") + "class ".length
                    val declarationIndex = TextIndex(declarationSource)
                    val callerIndex = TextIndex(text)
                    val call = text.indexOf("new $alias") + "new ".length
                    val imported = text.indexOf(name)
                    for (includeDeclaration in listOf(false, true)) {
                        val expected = listOf(
                            location(caller, callerIndex, imported, name),
                            location(caller, callerIndex, call, alias),
                        ) + if (includeDeclaration) {
                            listOf(location(file, declarationIndex, declaration, name))
                        } else {
                            emptyList()
                        }
                        val queries = listOf(
                            file.toUri().toString() to declarationIndex.positionOf(declaration + 1),
                            caller.toUri().toString() to callerIndex.positionOf(call + 1),
                        )
                        queries.forEach { (uri, position) ->
                            val actual = ReferenceProvider(service, index)
                                .provideReferences(uri, position, includeDeclaration).toList()
                            assertEquals(
                                expected.toSet(),
                                actual.toSet(),
                                "$name cached=$cached includeDeclaration=$includeDeclaration origin=$uri",
                            )
                            assertEquals(expected.size, actual.size)
                        }
                    }
                }
            }
        } finally {
            service.clearCaches()
        }
    }

    private suspend fun compileSources(service: GroovyCompilationService, sources: Map<Path, String>) {
        sources.forEach { (file, source) ->
            service.compile(file.toUri(), source)
            assertTrue(
                assertNotNull(service.getParseResult(file.toUri())).outcome.achievedPhase >= Phases.SEMANTIC_ANALYSIS,
            )
        }
    }

    private fun fixture(): Fixture {
        val root = Files.createDirectories(workspace.resolve("src/p"))
        val outer = root.resolve("Outer.groovy")
        val dollar = root.resolve("Dollar\$Part.groovy")
        val caller = root.resolve("Caller.groovy")
        val sources = mapOf(
            outer to "package p\nclass Outer { static class Inner {} }\n",
            dollar to "package p\nclass Dollar\$Part {}\n",
            caller to "package p\nimport p.Outer.Inner as Nested\nimport p.Dollar\$Part as Money\n" +
                "class Caller { def make() { new Nested(); new Money() } }\n",
        )
        sources.forEach { (file, source) -> Files.writeString(file, source) }
        return Fixture(outer, dollar, caller, sources)
    }

    private data class Fixture(val outer: Path, val dollar: Path, val caller: Path, val sources: Map<Path, String>)

    private fun location(file: Path, index: TextIndex, offset: Int, name: String): Location =
        Location(file.toUri().toString(), Range(index.positionOf(offset), index.positionOf(offset + name.length)))
}
