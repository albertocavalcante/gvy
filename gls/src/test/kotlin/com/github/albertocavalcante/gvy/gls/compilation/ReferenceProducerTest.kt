// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

class ReferenceProducerTest {
    @Test
    fun `a strategy classpath change cannot reuse earlier indexed bindings`(@TempDir root: Path): Unit = runBlocking {
        val paths = AtomicReference<List<Path>>(emptyList())
        val manager = WorkspaceManager().apply { setTestClasspath(root, paths::get) }
        val uri = root.resolve("ReferenceProducer.groovy").toUri()
        val text = "class ReferenceProducer { def target() {} }"
        val documents = DocumentProvider().apply { put(uri, text) }
        val compilation = GroovyCompilationService(documentProvider = documents, workspaceManager = manager)
        try {
            compilation.compile(uri, text)
            val first = compilation.referenceReadiness(uri).query { it.index.getDocumentSnapshot(uri)?.sourceText }
            assertEquals(text, assertIs<SnapshotQuery.Answer<*>>(first).value)
            val fingerprint = manager.fingerprintFor(uri)
            val next = listOf(Files.createDirectory(root.resolve("new-classpath")))
            val changed = compilation.referenceReadiness(uri).query {
                paths.set(next)
                emptyList<String>()
            }
            assertSame(SnapshotQuery.Stale, changed)
            assertEquals(fingerprint, manager.fingerprintFor(uri))
            val later = compilation.referenceReadiness(uri).query { error("A different producer reached the query") }
            assertIs<SnapshotQuery.Failure>(later)

            // A cached parse produced for the first path must not publish fresh bindings for the second.
            compilation.compile(uri, text)
            val refreshed = compilation.referenceReadiness(uri).query { qualified ->
                val held = assertIs<SnapshotAcquisition.Available>(compilation.acquireSnapshot(uri)).snapshot
                qualified.matches(uri, held)
            }
            assertEquals(true, assertIs<SnapshotQuery.Answer<*>>(refreshed).value)

            // The classpath can return to the indexed one after a different worker invocation read it.
            val mismatch = compilation.referenceReadiness(uri).query { qualified ->
                paths.set(emptyList())
                val different = assertIs<SnapshotAcquisition.Available>(compilation.acquireSnapshot(uri)).snapshot
                paths.set(next)
                qualified.matches(uri, different)
            }
            assertFalse(assertIs<SnapshotQuery.Answer<Boolean>>(mismatch).value)
        } finally {
            compilation.shutdown()
        }
    }
}
