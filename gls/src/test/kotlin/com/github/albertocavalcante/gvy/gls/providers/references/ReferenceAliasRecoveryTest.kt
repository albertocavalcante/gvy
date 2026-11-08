package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentService
import com.github.albertocavalcante.gvy.gls.services.GroovyTextDocumentServiceOptions
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class ReferenceAliasRecoveryTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `class aliases do not abort reference lookup in other files`(): Unit = runBlocking {
        supervisorScope {
            val declaration = workspace.resolve("Defs.groovy")
            val caller = workspace.resolve("Caller.groovy")
            val source = "import Foo as Alias\nclass Foo {}\ndef a = new Foo()\ndef b = new Alias()\n"
            val callerSource = "class Caller { Foo create() { new Foo() } }\n"
            Files.writeString(declaration, source)
            Files.writeString(caller, callerSource)
            val documents = DocumentProvider().apply {
                put(declaration.toUri(), source, 1)
                put(caller.toUri(), callerSource, 1)
            }
            val compilation = GroovyCompilationService(documentProvider = documents)
            compilation.workspaceManager.initializeWorkspace(workspace)
            compilation.workspaceManager.updateWorkspaceModel(workspace, emptyList(), listOf(workspace))
            try {
                compilation.getWorkspaceCompiler().compileWorkspace()
                val endpoint = GroovyTextDocumentService(
                    this,
                    compilation,
                    GroovyTextDocumentServiceOptions(documentProvider = documents),
                )
                val actual = endpoint.references(
                    ReferenceParams(
                        TextDocumentIdentifier(declaration.toUri().toString()),
                        TextIndex(source).positionOf(source.indexOf("class Foo") + "class ".length),
                        ReferenceContext(true),
                    ),
                ).await()
                val index = TextIndex(callerSource)
                val offset = callerSource.lastIndexOf("Foo")
                val expected = Location(
                    caller.toUri().toString(),
                    Range(index.positionOf(offset), index.positionOf(offset + "Foo".length)),
                )
                assertTrue(expected in actual, "The alias occurrence must not discard the workspace caller: $actual")
            } finally {
                compilation.clearCaches()
            }
        }
    }
}
