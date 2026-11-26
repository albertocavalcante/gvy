// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.build.SourceArtifactResolver
import com.github.albertocavalcante.gvy.gls.sources.SourceJarExtractor
import com.github.albertocavalcante.gvy.gls.sources.SourceNavigationService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals

class BinaryHoverSourceAttachmentTest {
    @Test
    fun `a loose versioned binary cannot acquire a source attachment from guessed Maven coordinates`(
        @TempDir root: Path,
    ): Unit = runBlocking {
        val classes = Files.createDirectory(root.resolve("classes"))
        val source = Files.writeString(root.resolve("Api.java"), "package p; public class Api {}")
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), source.toString()))
        val binary = root.resolve("library-1.2.jar")
        writeJar(binary, "p/Api.class", Files.readAllBytes(classes.resolve("p/Api.class")))
        val guessed = root.resolve("unrelated-sources.jar")
        val sibling = root.resolve("library-1.2-sources.jar")
        writeSourceJar(guessed, "Unrelated Maven artifact.")
        writeSourceJar(sibling, "Selected local artifact.")
        val resolver = mockk<SourceArtifactResolver>()
        coEvery { resolver.resolveSourceJar("library", "library", "1.2") } returns guessed
        val navigator = SourceNavigationService(resolver, SourceJarExtractor(root.resolve("extracted")))

        URLClassLoader(arrayOf(binary.toUri().toURL()), null).use { loader ->
            val selected = loader.loadClass("p.Api")
            assertEquals(binary.toUri().toURL(), selected.protectionDomain.codeSource.location)
            val withAttachment = navigator.documentationFor(selected)?.summary
            Files.delete(sibling)
            val withoutAttachment = navigator.documentationFor(selected)?.summary
            assertEquals(listOf("Selected local artifact.", null), listOf(withAttachment, withoutAttachment))
        }
    }

    private fun writeSourceJar(path: Path, prose: String) =
        writeJar(path, "p/Api.java", "package p; /** $prose */ public class Api {}".toByteArray())

    private fun writeJar(path: Path, name: String, bytes: ByteArray) {
        JarOutputStream(Files.newOutputStream(path)).use {
            it.putNextEntry(JarEntry(name))
            it.write(bytes)
        }
    }
}
