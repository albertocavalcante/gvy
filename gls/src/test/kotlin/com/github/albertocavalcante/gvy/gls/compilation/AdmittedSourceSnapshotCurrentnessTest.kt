// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdmittedSourceSnapshotCurrentnessTest {
    private val uri = URI.create("file:///snapshot/Money.groovy")
    private val original = "class Money { String currency }"

    @Test
    fun `capture refuses a cached parse of the previous buffer text`(): Unit = runBlocking {
        val documents = DocumentProvider()
        val service = GroovyCompilationService(documentProvider = documents)
        documents.put(uri, original, 1)
        assertTrue(service.compile(uri, original).isSuccess)
        assertNotNull(service.getAdmittedCompiledSnapshot(uri) {})

        documents.update(uri, "class Money { int currency }", 2)

        assertNull(service.getAdmittedCompiledSnapshot(uri) {})
    }

    @Test
    fun `unmanaged source capture retains a matching compiled parse`(): Unit = runBlocking {
        val service = GroovyCompilationService()
        assertTrue(service.compile(uri, original).isSuccess)

        assertNotNull(service.getAdmittedCompiledSnapshot(uri) {})
    }

    @Test
    fun `a new buffer revision retires an earlier snapshot even when text is equal`(): Unit = runBlocking {
        val documents = DocumentProvider()
        val service = GroovyCompilationService(documentProvider = documents)
        documents.put(uri, original, 1)
        assertTrue(service.compile(uri, original).isSuccess)
        val captured = assertNotNull(service.getAdmittedCompiledSnapshot(uri) {})
        assertTrue(service.isAdmittedCompiledSnapshotCurrent(uri, captured) {})

        documents.update(uri, original, 2)

        assertFalse(service.isAdmittedCompiledSnapshotCurrent(uri, captured) {})
        assertNotNull(service.getAdmittedCompiledSnapshot(uri) {})
    }

    @Test
    fun `close and reopen with equal version and text does not revive a captured snapshot`(): Unit = runBlocking {
        val documents = DocumentProvider()
        val service = GroovyCompilationService(documentProvider = documents)
        documents.put(uri, original, 1)
        assertTrue(service.compile(uri, original).isSuccess)
        val captured = assertNotNull(service.getAdmittedCompiledSnapshot(uri) {})

        documents.remove(uri)
        documents.put(uri, original, 1)

        assertFalse(service.isAdmittedCompiledSnapshotCurrent(uri, captured) {})
    }

    @Test
    fun `retiring the cache invalidates a held snapshot`(): Unit = runBlocking {
        val service = GroovyCompilationService()
        assertTrue(service.compile(uri, original).isSuccess)
        val captured = assertNotNull(service.getAdmittedCompiledSnapshot(uri) {})

        service.clearCaches()

        assertFalse(service.isAdmittedCompiledSnapshotCurrent(uri, captured) {})
    }
}
