// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.sources.SourceDocumentDescriptor
import com.github.albertocavalcante.gvy.gls.sources.SourceOrigin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.URL
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceRequestCaptureTest {
    @TempDir
    lateinit var root: Path

    private val uri = URI.create("file:///source-context/Money.groovy")
    private val original = "class Money { String currency }"

    @Test
    fun `a request captures JDK origin and retires with its buffer revision`(): Unit = runBlocking {
        val documents = DocumentProvider()
        val service = GroovyCompilationService(documentProvider = documents)
        try {
            documents.put(uri, original, 1)
            assertTrue(service.compile(uri, original).isSuccess)
            val request = assertNotNull(service.captureSourceRequest(uri, "java.lang.String"))
            assertEquals("java.base", assertIs<SourceOrigin.Jdk>(request.symbol.origin).module)
            assertTrue(request.context.isCurrent())

            documents.update(uri, original, 2)

            assertFalse(request.context.isCurrent())
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `source context survives ordinary edits but not a different logical environment`(): Unit = runBlocking {
        val documents = DocumentProvider()
        val service = GroovyCompilationService(documentProvider = documents)
        try {
            documents.put(uri, original, 1)
            assertTrue(service.compile(uri, original).isSuccess)
            val request = assertNotNull(service.captureSourceRequest(uri, "java.lang.String"))
            val descriptor = descriptor(request.symbol.origin, request.context.identity)

            val edited = "class Money { String currency; int amount }"
            documents.update(uri, edited, 2)
            assertTrue(service.compile(uri, edited).isSuccess)

            val recaptured = assertNotNull(service.recaptureSourceRequest("java.lang.String", descriptor))
            assertEquals(request.context.identity, recaptured.context.identity)
            assertTrue(recaptured.context.isCurrent())
            assertFalse(request.context.isCurrent())
            assertIs<SourceRequestSelection.ContextUnavailable>(
                service.selectSourceType(
                    "java.lang.String",
                    descriptor.copy(context = descriptor.context.copy(id = "another-context")),
                ),
            )
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `only current exact class-resource absence is reported as binary absent`(): Unit = runBlocking {
        val documents = DocumentProvider()
        val service = GroovyCompilationService(documentProvider = documents)
        try {
            documents.put(uri, original, 1)
            assertTrue(service.compile(uri, original).isSuccess)
            val request = assertNotNull(service.captureSourceRequest(uri, "java.lang.String"))
            val descriptor = descriptor(request.symbol.origin, request.context.identity)

            assertIs<SourceRequestSelection.BinaryAbsent>(service.selectSourceType("missing.DoesNotExist", descriptor))
            documents.update(uri, "class Money {}", 2)
            assertIs<SourceRequestSelection.ContextUnavailable>(
                service.selectSourceType("missing.DoesNotExist", descriptor),
            )
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `resource lookup uses the held native loader and accepts supplementary identifiers`(): Unit = runBlocking {
        val name = "sample.\uD801\uDC00Name"
        val resource = root.resolve("selected.class").toUri().toURL()
        val loader = object : ClassLoader(ClassLoader.getPlatformClassLoader()) {
            override fun getResource(resourceName: String): URL? =
                if (resourceName == name.replace('.', '/') + ".class") resource else super.getResource(resourceName)
        }
        val service = GroovyCompilationService(parentClassLoader = loader)
        try {
            assertTrue(service.compile(uri, original).isSuccess)
            val request = assertNotNull(service.captureSourceRequest(uri, name))
            assertEquals(resource.toURI(), assertIs<SourceOrigin.Unknown>(request.symbol.origin).resource)
            assertNull(service.captureSourceRequest(uri, "../invalid"))
        } finally {
            service.shutdown()
        }
    }

    private fun descriptor(
        origin: SourceOrigin,
        context: com.github.albertocavalcante.gvy.gls.sources.SourceContextIdentity,
    ) = SourceDocumentDescriptor(
        "a".repeat(64),
        "b".repeat(64),
        "java/lang/String.java",
        root.resolve("content.java"),
        origin,
        context,
        "JDK java.base",
        "java",
    )
}
