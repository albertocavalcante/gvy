// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.CompiledDocumentInput
import com.github.albertocavalcante.nativeapi.ParseRequest
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
            val selected = assertNotNull(CompiledDocumentInput.of(module))
            assertTrue(selected.matchesText(text))
            assertFalse(selected.matchesText("\uFEFF$text"))
            assertTrue(selected.matchesCurrentText(null) {})
            Files.writeString(file, "\uFEFF" + text.replace("😀", "🙃"))
            assertFalse(selected.matchesCurrentText(null) {})
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
