// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.e2e

import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.JarURLConnection
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PackagedEditorReferenceTest {
    @Test
    fun `the packaged compiler resolves the Groovy SDK annotation from its original component`() {
        val jar = packagedJar()
        val annotation = "groovy.transform.Memoized"
        val entry = annotation.replace('.', '/') + ".class"
        val expected = JarFile(jar.toFile()).use { archive ->
            archive.getInputStream(assertNotNull(archive.getJarEntry(entry))).use { it.readBytes() }
        }
        val platform = ClassLoader.getPlatformClassLoader()
        // Load the installed compiler in isolation, so Gradle's separate Groovy dependency cannot satisfy lookup.
        URLClassLoader(arrayOf(jar.toUri().toURL()), platform).use { application ->
            val type = application.loadClass("com.github.albertocavalcante.groovyparser.SdkFallbackGroovyClassLoader")
            val compiler = type.getConstructor(ClassLoader::class.java).newInstance(platform) as URLClassLoader
            compiler.use {
                type.getMethod("selectSdkFallback").invoke(compiler)
                val resource = assertNotNull(compiler.getResource(entry), "Compiler cannot resolve its SDK annotation")
                val connection = resource.openConnection() as JarURLConnection
                connection.useCaches = false
                assertEquals(jar, Path.of(connection.jarFileURL.toURI()).toRealPath())
                assertContentEquals(expected, connection.getInputStream().use { it.readBytes() })
                val resolved = compiler.loadClass(annotation)
                assertEquals(jar, Path.of(resolved.protectionDomain.codeSource.location.toURI()).toRealPath())
                assertTrue(resolved.isAnnotation)
            }
        }
    }

    @Test
    fun `a Memoized companion preserves exact incoming callers on the packaged server`(@TempDir workspace: Path) {
        val line = "/* 😀😀 */ class EditorUnicodeCalls { def target() {}; /* 😀😀😀 */ def caller() { target() } }"
        val source = """
            package editor.implementation
            class EditorRenameBindings {
                @groovy.transform.Memoized
                String memoized(String value) { value }
            }
        """.trimIndent() + "\n" + line
        val tokens = "/* 😀 */ class TokenExample {\n    String echo(String value) {\n        return value\n    }\n}\n"
        val origin = Files.writeString(workspace.resolve("Implementation.groovy"), source).toUri().toString()
        val companion = Files.writeString(workspace.resolve("SemanticTokens.groovy"), tokens).toUri().toString()
        val lineNumber = source.lines().lastIndex
        fun token(text: String, start: Int = 0): Range {
            val at = line.indexOf(text, start)
            require(at >= 0) { "Missing fixture token $text" }
            return Range(Position(lineNumber, at), Position(lineNumber, at + text.length))
        }
        LanguageServerSessionFactory().start(ServerConfig(), "packaged-editor-reference").use { session ->
            val args = assertNotNull(session.process).info().arguments().orElseThrow().toList()
            val jar = args.indexOf("-jar")
            assertTrue(jar >= 0)
            assertEquals(packagedJar(), Path.of(args[jar + 1]).toRealPath())
            val server = session.server
            server.initialize(
                InitializeParams().apply {
                    capabilities = ClientCapabilities()
                    workspaceFolders = listOf(WorkspaceFolder(workspace.toUri().toString(), "editor"))
                },
            ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            server.initialized(InitializedParams())
            session.client.readyFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val editor = server.textDocumentService
            for ((uri, text) in listOf(origin to source, companion to tokens)) {
                editor.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "groovy", 1, text)))
            }
            val name = token("target")
            val item = editor.prepareCallHierarchy(
                CallHierarchyPrepareParams(TextDocumentIdentifier(origin), name.start),
            )
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).single()
            assertEquals(name, item.selectionRange)
            assertEquals(token("def target() {}"), item.range)
            val incoming = editor.callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(listOf("caller"), incoming.map { it.from.name })
            val from = incoming.single()
            assertEquals(origin, from.from.uri)
            assertEquals(token("def caller() { target() }"), from.from.range)
            assertEquals(listOf(token("target", from.from.selectionRange.end.character)), from.fromRanges)
            server.shutdown().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            session.exit()
        }
    }

    private fun packagedJar(): Path = requireNotNull(
        BazelRunfiles.resolve(requireNotNull(System.getProperty("groovy.lsp.e2e.execJar"))),
    ).toRealPath()

    private companion object {
        const val TIMEOUT_SECONDS = 30L
    }
}
