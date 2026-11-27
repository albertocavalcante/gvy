// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.documentation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import org.codehaus.groovy.ast.ClassNode
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Groovydoc comes from the buffer as it is at each lookup. */
class DocumentationProviderTest {
    private val uri = URI.create("file:///docs/Greeter.groovy")
    private val node = ClassNode("Greeter", 0, null).apply { lineNumber = 4 }

    @Test
    fun `each lookup reads the buffer as it is now`() {
        val documents = DocumentProvider()
        val provider = DocumentationProvider(documents)
        val old = DocExtractor.extractDocumentation(source("Greets in English."), node)
        val current = DocExtractor.extractDocumentation(source("Greets in Portuguese."), node)
        assertNotEquals(old, current)

        documents.put(uri, source("Greets in English."))
        assertEquals(old, provider.getDocumentation(node, uri))
        documents.put(uri, source("Greets in Portuguese."))
        assertEquals(current, provider.getDocumentation(node, uri), "an edit is seen by the next lookup")
    }

    @Test
    fun `a document that is no longer open has no documentation`() {
        val documents = DocumentProvider()
        val provider = DocumentationProvider(documents)
        documents.put(uri, source("Greets in English."))
        val english = DocExtractor.extractDocumentation(source("Greets in English."), node)
        assertNotEquals(Documentation.EMPTY, english)
        assertEquals(english, provider.getDocumentation(node, uri), "the open buffer has documentation")

        documents.remove(uri)

        assertEquals(Documentation.EMPTY, provider.getDocumentation(node, uri))
    }

    @Test
    fun `a node without a line has no documentation`() {
        val documents = DocumentProvider().apply { put(uri, source("Greets in English.")) }
        val lineless = ClassNode("Greeter", 0, null)

        assertEquals(Documentation.EMPTY, DocumentationProvider(documents).getDocumentation(lineless, uri))
    }

    private fun source(summary: String) = "/**\n * $summary\n */\nclass Greeter {}\n"
}
