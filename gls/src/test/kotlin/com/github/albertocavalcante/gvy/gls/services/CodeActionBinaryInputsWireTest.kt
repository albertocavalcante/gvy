// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.spock.SpecificationEvidence
import com.github.albertocavalcante.gvy.spock.SpecificationStatus
import com.github.albertocavalcante.gvy.spock.SpockSourceContext
import com.github.albertocavalcante.nativeapi.NativeArtifactStamp
import com.github.albertocavalcante.nativeapi.ParseResult
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CodeActionBinaryInputsWireTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `replacing the jar after Spock classification rejects the held edit`() {
        val jars = BinaryBases(workspace)
        val project = SpockTableWireProject(workspace, "dep.Base")
        SpockInputWireSession(project).use { wire ->
            wire.open(listOf(jars.path))
            val held = wire.owned()
            jars.assertBinding(held, specification = true)
            val owner = SpockSourceContext.from(assertNotNull(held.ast), isPartial = false).classes.single()
            assertEquals(SpecificationEvidence.RESOLVED_HIERARCHY, owner.evidence)
            wire.assertTable(wire.service())
            val changed = AtomicBoolean()
            val during = wire.service(Formatter { text ->
                assertSame(held, wire.owned())
                jars.replace()
                changed.set(true)
                text
            })
            val failure = deliveryFailure { wire.tables(during) }
            assertTrue(changed.get(), "formatter must run after table generation")
            wire.edit()
            jars.assertReplacementClass(wire.owned())
            assertEquals(ResponseErrorCode.ContentModified.value, failure, "changed binary supplied the old edit")
        }
    }

    @Test
    fun `a broken source base cannot recall a clean verdict after its binary superclass changed`() {
        val jars = BinaryBases(workspace)
        val project = sourceProject()
        SpockInputWireSession(project).use { wire ->
            wire.open(listOf(jars.path))
            val service = wire.service()
            jars.assertBinding(wire.owned(), specification = true)
            wire.assertTable(service)
            wire.breakSourceBase()
            wire.assertTable(service)
            jars.replace()
            wire.edit()
            val freshDegraded = wire.owned()
            assertTrue(freshDegraded.outcome.isDegraded)
            jars.assertReplacementClass(freshDegraded)
            val answer = wire.tables(service)
            Files.writeString(project.base, SOURCE_BASE)
            wire.edit()
            jars.assertReplacementClass(wire.owned())
            assertTrue(answer.isEmpty(), "the historic JVM binding belonged to the previous jar")
        }
    }

    @Test
    fun `an invalidated clean request cannot seed a later degraded verdict`() {
        val jars = BinaryBases(workspace)
        val project = sourceProject()
        SpockInputWireSession(project).use { wire ->
            wire.open(listOf(jars.path))
            jars.assertBinding(wire.owned(), specification = true)
            val control = wire.service()
            wire.assertTable(control)
            val first = AtomicBoolean(true)
            val during = wire.service(Formatter { text ->
                if (first.compareAndSet(true, false)) jars.replace()
                text
            })
            // The immediate error is independently asserted above. Here the observable is verdict publication.
            val failure = deliveryFailure { wire.tables(during) }
            if (failure != null) assertEquals(ResponseErrorCode.ContentModified.value, failure)
            assertFalse(first.get())
            wire.edit()
            jars.assertReplacementClass(wire.owned())
            jars.restore()
            wire.breakSourceBase()
            wire.assertTable(control)
            val recalled = wire.tables(during)
            assertTrue(recalled.isEmpty(), "the request spanning the replacement never owned a clean verdict")
        }
    }

    @Test
    fun `the next compiler hierarchy agrees with the class loaded from the replaced jar`() {
        val jars = BinaryBases(workspace)
        val project = SpockTableWireProject(workspace, "dep.Base")
        SpockInputWireSession(project).use { wire ->
            wire.open(listOf(jars.path))
            jars.assertBinding(wire.owned(), specification = true)
            jars.replace()
            wire.edit()
            val fresh = wire.owned()
            jars.assertReplacementClass(fresh)
            jars.assertBinding(fresh, specification = false)
        }
    }

    private fun sourceProject() = SpockTableWireProject(workspace, "example.SourceBase").also {
        Files.writeString(it.base, SOURCE_BASE)
    }

    private fun deliveryFailure(query: () -> Unit): Int? = try {
        query()
        null
    } catch (error: ExecutionException) {
        assertIs<ResponseErrorException>(error.cause).responseError.code
    }

    private class BinaryBases(private val workspace: Path) {
        val path = build("original", "spock.lang.Specification")
        private val plain = build("replacement", "Object")
        private val saved = workspace.resolve("saved.jar")
        private val original = assertNotNull(NativeArtifactStamp.observe(path))

        fun replace() {
            Files.move(path, saved)
            Files.move(plain, path)
            val newer = FileTime.fromMillis(original.lastModifiedMillis + TimeUnit.SECONDS.toMillis(2))
            Files.setLastModifiedTime(path, newer)
            val replaced = assertNotNull(NativeArtifactStamp.observe(path))
            assertNotEquals(original.size, replaced.size, "the same path now contains a different physical artifact")
        }

        fun restore() {
            Files.move(saved, path, StandardCopyOption.REPLACE_EXISTING)
            assertEquals(original, NativeArtifactStamp.observe(path))
        }

        fun assertReplacementClass(parsed: ParseResult) {
            val loaded = parsed.compilationUnit.classLoader.loadClass("dep.Base", false, true, false)
            assertTrue(Files.isSameFile(path, Path.of(loaded.protectionDomain.codeSource.location.toURI())))
            assertEquals("java.lang.Object", loaded.superclass.name)
            val inputs = assertNotNull(parsed.classpathInputs)
            val captured = assertNotNull(inputs.classpathStamps[inputs.classpath.indexOf(path)])
            assertNotEquals(original, captured)
            assertEquals(NativeArtifactStamp.observe(path), captured)
        }

        fun assertBinding(parsed: ParseResult, specification: Boolean) {
            assertFalse(parsed.outcome.isDegraded, "${parsed.outcome}")
            val module = assertNotNull(parsed.ast)
            val owner = module.classes.single { it.name == "TableSpec" }
            val parent = owner.superClass.redirect()
            val binary = if (parent.name == "dep.Base") parent else parent.superClass.redirect()
            assertEquals("dep.Base", binary.name)
            assertTrue(binary.isResolved)
            assertTrue(Files.isSameFile(path, Path.of(binary.typeClass.protectionDomain.codeSource.location.toURI())))
            assertEquals(if (specification) "spock.lang.Specification" else "java.lang.Object", binary.superClass.name)
            val status = SpockSourceContext.from(module, isPartial = false).classes.single().status
            val expected = if (specification) {
                SpecificationStatus.SPECIFICATION
            } else {
                SpecificationStatus.NOT_SPECIFICATION
            }
            assertEquals(expected, status)
            val inputs = assertNotNull(parsed.classpathInputs)
            val stamp = assertNotNull(inputs.classpathStamps[inputs.classpath.indexOf(path)])
            assertEquals(NativeArtifactStamp.observe(path), stamp)
        }

        private fun build(directory: String, superclass: String): Path {
            val dir = Files.createDirectory(workspace.resolve(directory))
            val config = CompilerConfiguration().apply { targetDirectory = dir.toFile() }
            GroovyClassLoader(javaClass.classLoader).use { loader ->
                CompilationUnit(config, null, loader).apply {
                    addSource("Base.groovy", "package dep\nabstract class Base extends $superclass {}")
                    compile()
                }
            }
            val jar = dir.resolve("base.jar")
            JarOutputStream(Files.newOutputStream(jar)).use { out ->
                out.putNextEntry(JarEntry("dep/Base.class"))
                Files.copy(dir.resolve("dep/Base.class"), out)
                out.closeEntry()
                if (superclass == "Object") {
                    out.putNextEntry(JarEntry("replacement.marker"))
                    out.write("ordinary in-place dependency replacement".toByteArray())
                    out.closeEntry()
                }
            }
            return jar
        }
    }

    private companion object {
        const val SOURCE_BASE = "package example\nabstract class SourceBase extends dep.Base {}\n"
    }
}
