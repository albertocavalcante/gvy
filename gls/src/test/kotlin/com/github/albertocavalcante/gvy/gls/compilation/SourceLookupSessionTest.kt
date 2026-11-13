// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.build.ArtifactStamp
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import com.github.albertocavalcante.gvy.gls.sources.SourceDocumentDescriptor
import com.github.albertocavalcante.gvy.gls.sources.SourceOrigin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceLookupSessionTest {
    @TempDir
    lateinit var root: Path

    private val uri = URI.create("file:///source-context/Money.groovy")
    private val content = "class Money { String currency }"

    @Test
    fun `a browser session never reacquires a newer document between candidates`(): Unit = runBlocking {
        val documents = DocumentProvider()
        val compilation = GroovyCompilationService(documentProvider = documents)
        try {
            documents.put(uri, content, 1)
            assertTrue(compilation.compile(uri, content).isSuccess)
            val request = assertNotNull(compilation.captureSourceRequest(uri, "java.lang.String"))
            val descriptor = descriptor(request.symbol.origin, request.context.identity)
            val held = assertNotNull(compilation.captureSourceLookupSession(descriptor))
            assertIs<SourceRequestSelection.Selected>(held.select("java.lang.String"))

            documents.update(uri, content, 2)
            assertTrue(compilation.compile(uri, content).isSuccess)

            assertFalse(held.isCurrent())
            assertIs<SourceRequestSelection.ContextUnavailable>(held.select("java.lang.Integer"))
            val nextRequest = assertNotNull(compilation.captureSourceLookupSession(descriptor))
            assertTrue(nextRequest.isCurrent())
            assertIs<SourceRequestSelection.Selected>(nextRequest.select("java.lang.Integer"))
        } finally {
            compilation.shutdown()
        }
    }

    @Test
    fun `the final session guard retains an earlier candidate archive stamp`(): Unit = runBlocking {
        val archive = root.resolve("selected.jar")
        Files.write(archive, byteArrayOf(1))
        val resource = URI.create("jar:${archive.toUri()}!/sample/First.class").toURL()
        val loader = object : ClassLoader(ClassLoader.getPlatformClassLoader()) {
            override fun getResource(name: String): URL? =
                if (name == "sample/First.class") resource else super.getResource(name)
        }
        val compilation = GroovyCompilationService(parentClassLoader = loader)
        try {
            assertTrue(compilation.compile(uri, content).isSuccess)
            val request = assertNotNull(compilation.captureSourceRequest(uri, "java.lang.String"))
            val held = assertNotNull(
                compilation.captureSourceLookupSession(descriptor(request.symbol.origin, request.context.identity)),
            )
            assertIs<SourceRequestSelection.Selected>(held.select("sample.First"))
            assertIs<SourceRequestSelection.BinaryAbsent>(held.select("sample.Absent"))
            assertTrue(held.isCurrent())

            Files.write(archive, byteArrayOf(1, 2))

            assertFalse(
                held.isCurrent(),
                "Later absent candidates must not discard an earlier selected archive's guard",
            )
            assertIs<SourceRequestSelection.ContextUnavailable>(held.select("sample.Absent"))
        } finally {
            compilation.shutdown()
        }
    }

    @Test
    fun `an existing source view cannot start a session after its original archive changes`(): Unit = runBlocking {
        val archive = root.resolve("original.jar")
        Files.write(archive, byteArrayOf(1))
        val resource = URI.create("jar:${archive.toUri()}!/sample/Original.class").toURL()
        val loader = object : ClassLoader(ClassLoader.getPlatformClassLoader()) {
            override fun getResource(name: String): URL? =
                if (name == "sample/Original.class") resource else super.getResource(name)
        }
        val compilation = GroovyCompilationService(parentClassLoader = loader)
        try {
            assertTrue(compilation.compile(uri, content).isSuccess)
            val request = assertNotNull(compilation.captureSourceRequest(uri, "sample.Original"))
            val view = descriptor(request.symbol.origin, request.context.identity)

            Files.write(archive, byteArrayOf(1, 2))

            assertNull(compilation.captureSourceLookupSession(view))
        } finally {
            compilation.shutdown()
        }
    }

    @Test
    fun `an existing source view rejects same-size archive rewrites with the original timestamp`(): Unit = runBlocking {
        val archive = root.resolve("same-stamp.jar")
        Files.write(archive, byteArrayOf(1, 2, 3, 4))
        val resource = URI.create("jar:${archive.toUri()}!/sample/Original.class").toURL()
        val loader = object : ClassLoader(ClassLoader.getPlatformClassLoader()) {
            override fun getResource(name: String): URL? =
                if (name == "sample/Original.class") resource else super.getResource(name)
        }
        val compilation = GroovyCompilationService(parentClassLoader = loader)
        try {
            assertTrue(compilation.compile(uri, content).isSuccess)
            val request = assertNotNull(compilation.captureSourceRequest(uri, "sample.Original"))
            val origin = assertIs<SourceOrigin.Archive>(request.symbol.origin)
            assertNotNull(origin.binarySha256)
            val view = descriptor(origin, request.context.identity)
            val originalStamp = assertNotNull(origin.observedStamp)
            val originalTime = Files.getLastModifiedTime(archive)

            Files.write(archive, byteArrayOf(4, 3, 2, 1))
            Files.setLastModifiedTime(archive, originalTime)

            assertEquals(originalStamp, ArtifactStamp.of(archive))
            assertNull(compilation.captureSourceLookupSession(view))
        } finally {
            compilation.shutdown()
        }
    }

    @Test
    fun `an open source session rejects same-size archive rewrites with the original timestamp`(): Unit = runBlocking {
        val archive = root.resolve("open-session-same-stamp.jar")
        Files.write(archive, byteArrayOf(1, 2, 3, 4))
        val resource = URI.create("jar:${archive.toUri()}!/sample/Original.class").toURL()
        val loader = object : ClassLoader(ClassLoader.getPlatformClassLoader()) {
            override fun getResource(name: String): URL? =
                if (name == "sample/Original.class") resource else super.getResource(name)
        }
        val compilation = GroovyCompilationService(parentClassLoader = loader)
        try {
            assertTrue(compilation.compile(uri, content).isSuccess)
            val request = assertNotNull(compilation.captureSourceRequest(uri, "sample.Original"))
            val origin = assertIs<SourceOrigin.Archive>(request.symbol.origin)
            val session = assertNotNull(
                compilation.captureSourceLookupSession(descriptor(origin, request.context.identity)),
            )
            val originalStamp = assertNotNull(origin.observedStamp)
            val originalTime = Files.getLastModifiedTime(archive)

            Files.write(archive, byteArrayOf(4, 3, 2, 1))
            Files.setLastModifiedTime(archive, originalTime)

            assertEquals(originalStamp, ArtifactStamp.of(archive))
            assertFalse(session.validateCurrent())
        } finally {
            compilation.shutdown()
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
