// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.implementation

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImplementationCoordinateProjectionTest {
    private val uri = URI.create("file:///ImplementationCoordinates.groovy")
    private val compilation = GroovyCompilationService()
    private val provider = ImplementationProvider(compilation)
    private val mixed = "// header\r// Target\ninterface Target {}\nclass Actual implements Target {}\n"

    @AfterEach
    fun close() {
        compilation.clearCaches()
    }

    @Test
    fun `comment names after bare CR cannot select a later interface`(): Unit = runBlocking {
        compilation.compile(uri, mixed)
        val locations = provider.provideImplementations(uri.toString(), Position(1, 4)).toList()
        assertTrue(locations.isEmpty(), "The comment identifier is not a declaration or reference")
    }

    @Test
    fun `mixed-line declarations navigate to correctly projected implementations`(): Unit = runBlocking {
        compilation.compile(uri, mixed)
        val locations = provider.provideImplementations(uri.toString(), Position(2, 11)).toList()
        assertEquals(1, locations.size)
        assertEquals(uri.toString(), locations.single().uri)
        assertEquals(Position(3, 0), locations.single().range.start)
    }

    @Test
    fun `supplementary text projects both reference cursor and returned range`(): Unit = runBlocking {
        val source = "/* 😀 */ interface Target {}\n/* 😀 */ class Actual implements Target {}\n"
        compilation.compile(uri, source)
        val locations = provider.provideImplementations(uri.toString(), Position(0, 24)).toList()
        assertEquals(1, locations.size)
        assertEquals(Position(1, 9), locations.single().range.start)
    }
}
