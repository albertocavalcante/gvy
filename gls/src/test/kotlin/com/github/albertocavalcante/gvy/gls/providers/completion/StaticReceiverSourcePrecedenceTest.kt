// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.completion

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.expr.ClassExpression
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StaticReceiverSourcePrecedenceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `workspace source members take precedence over stale binary for a static receiver`(): Unit = runBlocking {
        val sourceRoot = Files.createDirectories(tempDir.resolve("workspace/src"))
        val sourceFile = sourceRoot.resolve("collision/Foo.groovy")
        Files.createDirectories(sourceFile.parent)
        val source = "package collision\nclass Foo { static String sourceOnly() { 'source' } }"
        Files.writeString(sourceFile, source)
        val binary = staleBinary()
        val compilation = GroovyCompilationService()
        try {
            compilation.updateWorkspaceModel(tempDir.resolve("workspace"), listOf(binary), listOf(sourceRoot))
            requireStaleBinary(compilation)
            publishSource(compilation, sourceFile, source)

            val request = "import collision.Foo\nFoo."
            val requestUri = sourceRoot.resolve("Use.groovy").toUri()
            val provenance = compilerBinding(compilation, requestUri, request)
            requireStaleBinary(compilation)
            val items = CompletionProvider.getContextualCompletions(
                uri = requestUri.toString(),
                line = 1,
                character = 4,
                compilationService = compilation,
                semanticResolver = SemanticTypeResolver(compilation.classpathService.getTypeSolver()),
                content = request,
            )
            val labels = items.map { it.label }
            assertTrue("sourceOnly" in labels, "Source member missing: $labels; compiler binding: $provenance")
            assertTrue("binaryOnly" !in labels, "Stale binary member leaked: $labels; compiler binding: $provenance")
        } finally {
            compilation.shutdown()
            compilation.clearCaches()
            compilation.classpathService.close()
        }
    }

    private fun requireStaleBinary(compilation: GroovyCompilationService) {
        val binaryClass = assertNotNull(compilation.classpathService.loadClass("collision.Foo"))
        assertTrue(binaryClass.methods.any { it.name == "binaryOnly" }, "Stale binary must remain loadable")
    }

    private suspend fun publishSource(compilation: GroovyCompilationService, sourceFile: Path, source: String) {
        val published = compilation.compile(sourceFile.toUri(), source)
        assertTrue(published.isSuccess, "Source must compile: ${published.diagnostics}")
        val index = assertNotNull(compilation.getWorkspaceSymbolIndex())
        assertTrue(
            index.getAllMembers("collision/Foo", includeInherited = true).any { it.name == "sourceOnly" },
            "Workspace index must contain the source-only member",
        )
    }

    private suspend fun compilerBinding(
        compilation: GroovyCompilationService,
        uri: URI,
        request: String,
    ): List<String> {
        val parsingCopy = CompletionContextDetector.insertDummyIdentifier(request, 1, 4, withDef = false)
        return compilation.compileTransient(uri, parsingCopy).astModel.getNodes(uri)
            .filterIsInstance<ClassExpression>().map {
                val owner = it.type.redirect()
                "${owner.name}:source=${owner.isPrimaryClassNode}:module=${owner.module?.context?.name}"
            }
    }

    private fun staleBinary(): Path {
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "A JDK compiler is required" }
        val javaDir = Files.createDirectories(tempDir.resolve("java/collision"))
        val classes = Files.createDirectories(tempDir.resolve("classes"))
        val javaSource = Files.writeString(
            javaDir.resolve("Foo.java"),
            "package collision; public class Foo { public static String binaryOnly() { return \"binary\"; } }",
        )
        check(
            compiler.run(
                null,
                null,
                null,
                "-proc:none",
                "--release",
                "17",
                "-d",
                classes.toString(),
                javaSource.toString(),
            ) == 0,
        )
        val jar = tempDir.resolve("stale.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry("collision/Foo.class"))
            Files.copy(classes.resolve("collision/Foo.class"), output)
            output.closeEntry()
        }
        return jar
    }
}
