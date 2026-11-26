// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.build.SourceArtifactResolver
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.sources.SourceJarExtractor
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigationService
import com.github.albertocavalcante.gvy.gls.worker.WorkerSession
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.ParseResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CrossFileBinaryAttachmentTest {
    @Test
    fun `definition selects source attached to the compiler's binary despite a cached namesake`(
        @TempDir root: Path,
    ): Unit = runBlocking {
        val selectedText = "package p;\n\npublic class Api {}\n"
        val otherText = "package p;\n// unrelated archive\n\n\npublic class Api {}\n"
        val source = Files.writeString(root.resolve("Api.java"), selectedText)
        val classes = Files.createDirectory(root.resolve("classes"))
        val compiler = assertNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), source.toString()))
        val selected = Files.createDirectory(root.resolve("selected"))
        val binary = selected.resolve("library.jar")
        archive(binary, "p/Api.class", Files.readAllBytes(classes.resolve("p/Api.class")))
        archive(selected.resolve("library-sources.jar"), "p/Api.java", selectedText.toByteArray())
        val other = Files.createDirectory(root.resolve("other")).resolve("library-sources.jar")
        archive(other, "p/Api.java", otherText.toByteArray())
        val extractor = SourceJarExtractor(root.resolve("extracted"))
        extractor.extractAndIndex(other)
        val namesake = assertNotNull(extractor.findSourceForClass("p.Api"))
        assertEquals(otherText, Files.readString(namesake))

        val resolver = mockk<SourceArtifactResolver>()
        coEvery { resolver.resolveSourceJar(any(), any(), any()) } returns null
        val navigator = SourceNavigationService(resolver, extractor)
        val uri = root.resolve("Imports.groovy").toUri()
        val text = "import p.Api\nclass Imports {}\n"
        val documents = DocumentProvider().apply { put(uri, text) }
        val selectedOwners = CopyOnWriteArrayList<URI>()
        val compilation = GroovyCompilationService(documentProvider = documents, workerSessionDecorator = { native ->
            object : WorkerSession {
                override fun parse(request: ParseRequest): ParseResult {
                    val result = native.parse(request)
                    if (request.uri == uri && request.compilePhase == Phases.CANONICALIZATION) {
                        val imported = assertNotNull(result.ast).imports.single { it.className == "p.Api" }
                        val selectedType = imported.type.redirect().typeClass
                        selectedOwners += selectedType.protectionDomain.codeSource.location.toURI()
                    }
                    return result
                }
            }
        })
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            compilation.updateWorkspaceModel(root, listOf(binary), emptyList())
            val options = GroovyTextDocumentServiceOptions(documentProvider = documents, sourceNavigator = navigator)
            val service = GroovyTextDocumentService(scope, compilation, options)
            val params = DefinitionParams(TextDocumentIdentifier(uri.toString()), Position(0, 10))
            val answer = service.definition(params).get(15, TimeUnit.SECONDS).left.single()
            assertTrue(selectedOwners.isNotEmpty())
            assertEquals(setOf(binary.toRealPath()), selectedOwners.map { Path.of(it).toRealPath() }.toSet())
            assertEquals(selectedText, Files.readString(Path.of(URI.create(answer.uri))))
            assertEquals(Range(Position(2, 0), Position(2, 0)), answer.range)
            assertEquals(otherText, Files.readString(namesake), "a previous location still identifies its own source")
        } finally {
            scope.cancel()
            compilation.shutdown()
        }
    }

    private fun archive(path: Path, name: String, bytes: ByteArray) {
        JarOutputStream(Files.newOutputStream(path)).use { output ->
            output.putNextEntry(JarEntry(name))
            output.write(bytes)
        }
    }
}
