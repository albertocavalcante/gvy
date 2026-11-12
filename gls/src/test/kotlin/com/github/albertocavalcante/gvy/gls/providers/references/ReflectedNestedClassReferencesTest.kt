// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.references

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.utils.TextIndex
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReflectedNestedClassReferencesTest {
    @Test
    fun `reflected nested class references retain source names`(): Unit = runBlocking {
        val source = "import java.util.Map\nclass Reflected {\n" +
            "def first = java.util.Map.Entry.class\ndef second = Map.Entry.class\n}\n"
        assertReferences(source)
    }

    @Test
    fun `intentional script nested class references retain resolved names`(): Unit = runBlocking {
        assertReferences("import java.util.Map\ndef first = java.util.Map.Entry.class\ndef second = Map.Entry.class\n")
    }

    private suspend fun assertReferences(source: String) {
        val uri = URI.create("file:///refs/Reflected.groovy")
        val service = GroovyCompilationService()
        try {
            service.compile(uri, source)
            assertTrue(assertNotNull(service.getParseResult(uri)).outcome.achievedPhase >= Phases.SEMANTIC_ANALYSIS)
            val index = TextIndex(source)
            val spans = Regex("\\bEntry\\b").findAll(source).map { it.range.first }.toList()
            assertEquals(2, spans.size)
            val expected = spans.map { offset ->
                Location(uri.toString(), Range(index.positionOf(offset), index.positionOf(offset + "Entry".length)))
            }
            for (includeDeclaration in listOf(true, false)) {
                val actual = ReferenceProvider(service).provideReferences(
                    uri.toString(),
                    index.positionOf(spans.first() + 1),
                    includeDeclaration,
                ).toList()
                assertEquals(expected.toSet(), actual.toSet())
                assertEquals(expected.size, actual.size)
            }
        } finally {
            service.clearCaches()
        }
    }
}
