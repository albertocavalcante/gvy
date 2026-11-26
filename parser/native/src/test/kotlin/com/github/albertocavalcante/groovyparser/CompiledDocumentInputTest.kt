// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.CompiledDocumentInput
import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.WorkspaceFileReaderSource
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.io.FileReaderSource
import org.codehaus.groovy.control.io.URLReaderSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompiledDocumentInputTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `selected file retains actual decoded text including BOM handling`() {
        val file = root.resolve("Base.groovy")
        val text = "// 😀\r\nclass Base {}\r\n"
        Files.writeString(file, "\uFEFF$text")
        val parsed = GroovyParserFacade().parse(request().copy(workspaceSources = listOf(file)))
        parsed.compilationUnit.classLoader.use {
            val base = assertNotNull(parsed.ast?.classes?.singleOrNull { it.name == "Child" }?.superClass?.redirect())
            assertEquals("Base", base.name)
            val module = assertNotNull(base.module)
            assertIs<FileReaderSource>(module.context.source)
            assertFalse(parsed.outcome.isDegraded)
            val selected = assertNotNull(CompiledDocumentInput.of(module))
            assertTrue(Files.isSameFile(file, Path.of(selected.uri)))
            assertTrue(selected.matchesText(text))
            assertFalse(selected.matchesText("\uFEFF$text"))
            assertTrue(selected.matchesCurrentText(null) {})
            Files.writeString(file, "\uFEFF" + text.replace("😀", "🙃"))
            assertFalse(selected.matchesCurrentText(null) {})
        }
    }

    @Test
    fun `workspace file readers respect the configured non UTF-8 decoder`() {
        val text = "// déjà vu\r\nclass Base {}\r\n"
        val file = Files.writeString(root.resolve("Base.groovy"), text, Charsets.ISO_8859_1)
        val config = CompilerConfiguration().apply { sourceEncoding = Charsets.ISO_8859_1.name() }
        val selected = WorkspaceFileReaderSource(file.toFile(), config)
        assertEquals(text, selected.reader.use { it.readText() })
    }

    @Test
    fun `a rejected file does not lend its identity to a synthetic replacement`() {
        val file = Files.writeString(root.resolve("Base.groovy"), "class Base { void broken( }")
        val parsed = GroovyParserFacade().parse(request().copy(workspaceSources = listOf(file)))
        parsed.compilationUnit.classLoader.use {
            assertTrue(parsed.outcome.excludedSources.any { it.source == file.toString() })
            assertTrue("Base" in parsed.outcome.stubbedClasses)
            val base = assertNotNull(parsed.ast?.classes?.singleOrNull { it.name == "Child" }?.superClass?.redirect())
            assertEquals("Base", base.name)
            assertNull(CompiledDocumentInput.of(assertNotNull(base.module)))
        }
    }

    @Test
    fun `implicit source-root resolution retains the selected URL reader`() {
        val file = root.resolve("Base.groovy")
        Files.writeString(file, "class Base {}")
        val parsed = GroovyParserFacade().parse(request().copy(sourceRoots = listOf(root)))
        parsed.compilationUnit.classLoader.use {
            val base = assertNotNull(parsed.ast?.classes?.singleOrNull { it.name == "Child" }?.superClass?.redirect())
            val module = assertNotNull(base.module)
            assertIs<URLReaderSource>(module.context.source)
            val input = assertNotNull(CompiledDocumentInput.of(module))
            assertTrue(Files.isSameFile(file, Path.of(input.uri)))
            assertTrue(input.matchesCurrentText(null) {})
            Files.delete(file)
            assertFalse(input.matchesCurrentText(null) {})
        }
    }

    @Test
    fun `open source selects the immutable editor text despite a conflicting disk copy`() {
        val file = root.resolve("Base.groovy")
        val disk = "class Base { String disk }"
        val editor = "class Base { String editor }"
        Files.writeString(file, disk)
        val parsed = GroovyParserFacade().parse(request().copy(openSources = mapOf(file.toUri() to editor)))
        parsed.compilationUnit.classLoader.use {
            val base = assertNotNull(parsed.ast?.classes?.singleOrNull { it.name == "Child" }?.superClass?.redirect())
            val input = assertNotNull(CompiledDocumentInput.of(assertNotNull(base.module)))
            assertEquals(file.toUri(), input.uri)
            assertTrue(input.matchesCurrentText(editor) {})
            assertFalse(input.matchesCurrentText(disk) {})
            assertFalse(input.matchesCurrentText(null) {})
        }
    }

    private fun request() = ParseRequest(
        root.resolve("Child.groovy").toUri(),
        "class Child extends Base {}",
        compilePhase = Phases.SEMANTIC_ANALYSIS,
    )
}
