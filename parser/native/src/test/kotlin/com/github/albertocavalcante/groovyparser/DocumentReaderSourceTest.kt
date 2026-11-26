// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser

import com.github.albertocavalcante.nativeapi.ParseRequest
import com.github.albertocavalcante.nativeapi.inputDocumentUri
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DocumentReaderSourceTest {
    @Test
    fun `source bindings retain the original buffer or file URI independently of Groovy source names`(
        @TempDir root: Path,
    ) {
        val caller = URI.create("untitled://editor/Client.groovy")
        val overlay = URI.create("custom://session/Overlay.groovy")
        val disk = Files.writeString(root.resolve("Disk.groovy"), "class Disk {}")
        val parsed = GroovyParserFacade().parse(
            ParseRequest(
                caller,
                "class Client { Overlay overlay; Disk disk }",
                openSources = mapOf(overlay to "class Overlay {}"),
                workspaceSources = listOf(disk),
                compilePhase = Phases.CANONICALIZATION,
            ),
        )
        try {
            val client = assertNotNull(parsed.ast).classes.single()
            val peer = client.getDeclaredField("overlay").type.redirect().module.context
            val file = client.getDeclaredField("disk").type.redirect().module.context
            assertEquals(caller, client.module.context.inputDocumentUri())
            assertEquals(overlay, peer.inputDocumentUri())
            assertEquals(disk.toUri(), file.inputDocumentUri())
            assertEquals("data", peer.source.getURI().scheme)
            assertNull(SourceUnit.create("/Overlay.groovy", "class Overlay {}").inputDocumentUri())
        } finally {
            parsed.compilationUnit.classLoader.close()
        }
    }
}
