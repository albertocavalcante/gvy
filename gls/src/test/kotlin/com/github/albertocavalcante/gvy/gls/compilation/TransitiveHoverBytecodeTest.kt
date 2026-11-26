// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.bytecodeOnlyClassLoader
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.JvmClasspathReflection
import groovy.lang.GroovyResourceLoader
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TransitiveHoverBytecodeTest {
    @Test
    fun `missing binary method signature cannot trigger source lookup through its defining loader`(
        @TempDir root: Path,
    ): Unit = runBlocking {
        val compiled = Files.createDirectory(root.resolve("classes"))
        val api = Files.writeString(root.resolve("Api.java"), "package p; public class Api { public Probe use(){return null;} }")
        val probe = Files.writeString(root.resolve("Probe.java"), "package p; public class Probe {}")
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "-d", compiled.toString(), api.toString(), probe.toString()))
        val jar = root.resolve("api.jar")
        JarOutputStream(Files.newOutputStream(jar)).use {
            it.putNextEntry(JarEntry("p/Api.class"))
            it.write(Files.readAllBytes(compiled.resolve("p/Api.class")))
        }

        val caller = root.resolve("Client.groovy").toUri()
        val docs = DocumentProvider().apply { put(caller, "class Client {}") }
        val manager = WorkspaceManager().apply { setTestClasspath(root) { listOf(jar) } }
        val service = GroovyCompilationService(documentProvider = docs, workspaceManager = manager)
        try {
            val held = assertIs<SnapshotAcquisition.Ready>(service.acquireEditorSnapshot(caller)).snapshot
            val loader = held.parseResult.compilationUnit.classLoader
            val view = bytecodeOnlyClassLoader(loader)
            val selected = view.loadClass("p.Api")
            assertSame(loader, selected.classLoader)
            assertEquals(jar.toUri().toURL(), selected.protectionDomain.codeSource.location)
            val probes = mutableListOf<String>()
            val previous = loader.resourceLoader
            try {
                loader.resourceLoader = GroovyResourceLoader { name ->
                    probes.add(name)
                    null
                }
                val reflection = JvmClasspathReflection { view }
                assertTrue(reflection.getMethods("p.Api").isEmpty(), "Signature type p.Probe is missing")
                assertEquals(emptyList(), probes, "The declaring class must not resolve a signature from source")
            } finally {
                loader.resourceLoader = previous
            }
        } finally {
            service.shutdown()
        }
    }
}
