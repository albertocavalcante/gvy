// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.diagnostics

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals

class UnusedImportSourceRangesTest {
    private val uri = URI.create("file:///ImportRanges.groovy")

    @Test
    fun `unused diagnostic uses editor CR lines instead of Groovy compiler LF lines`() = runBlocking {
        val compilation = GroovyCompilationService(
            parentClassLoader = groovy.transform.CompileStatic::class.java.classLoader,
        )
        try {
            val source = "import groovy.transform.Immutable\r" +
                "import java.util.List\r@Immutable\rclass Money { BigDecimal amount }\r"
            val held = compilation.compile(uri, source)
            val diagnostic = UnusedImportDiagnosticProvider(compilation)
                .provideDiagnostics(uri, source, held)
                .toList()
                .single()
            assertEquals(1, diagnostic.range.start.line)
            assertEquals(0, diagnostic.range.start.character)
            assertEquals(1, diagnostic.range.end.line)
            assertEquals("import java.util.List".length, diagnostic.range.end.character)
        } finally {
            compilation.shutdown()
        }
    }

    @Test
    fun `supplementary characters and tabs before an import preserve exact UTF16 columns`() = runBlocking {
        val compilation = GroovyCompilationService(
            parentClassLoader = groovy.transform.CompileStatic::class.java.classLoader,
        )
        try {
            val source = "/*😀*/\timport java.util.List\rclass Money {}\r"
            val held = compilation.compile(uri, source)
            val diagnostic = UnusedImportDiagnosticProvider(compilation)
                .provideDiagnostics(uri, source, held)
                .toList()
                .single()
            assertEquals(source.indexOf("import"), diagnostic.range.start.character)
            assertEquals(source.indexOf('\r'), diagnostic.range.end.character)
        } finally {
            compilation.shutdown()
        }
    }
}
