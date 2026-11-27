// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.documentation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import io.mockk.every
import io.mockk.mockk
import org.codehaus.groovy.ast.ClassNode
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The groovydoc cache never answers for text other than the buffer's current text. */
class DocumentationProviderTest {
    private val uri = URI.create("file:///docs/Greeter.groovy")
    private val node = ClassNode("Greeter", 0, null).apply { lineNumber = 4 }

    @Test
    fun `a lookup that raced an edit does not leave the old documentation cached`() {
        val documents = DocumentProvider()
        documents.put(uri, source("Greets in English."))
        val race = EditAfterFirstRead(documents, uri, source("Greets in Portuguese."))
        val racing = mockk<DocumentProvider> {
            every { get(uri) } answers { race.read()?.content }
            every { getSnapshot(uri) } answers { race.read() }
        }
        val provider = DocumentationProvider(racing)
        val old = DocExtractor.extractDocumentation(source("Greets in English."), node)
        val current = DocExtractor.extractDocumentation(source("Greets in Portuguese."), node)
        assertNotEquals(old, current)

        assertEquals(old, provider.getDocumentation(node, uri), "the first lookup answers for the text it read")
        assertEquals(current, provider.getDocumentation(node, uri), "the next lookup answers for the edited text")
    }

    private fun source(summary: String) = "/**\n * $summary\n */\nclass Greeter {}\n"

    /**
     * Returns the buffer as it was when read, and applies one edit right after the first read: the edit, and the
     * invalidation it sends, land while that lookup is still extracting from the text it read. The invalidation
     * finds nothing cached yet, so only the lookup's own store follows it.
     */
    private class EditAfterFirstRead(
        private val documents: DocumentProvider,
        private val uri: URI,
        private val edit: String,
    ) {
        private var edited = false

        fun read(): DocumentSnapshot? {
            val snapshot = documents.getSnapshot(uri)
            if (!edited) {
                edited = true
                documents.put(uri, edit)
            }
            return snapshot
        }
    }
}
