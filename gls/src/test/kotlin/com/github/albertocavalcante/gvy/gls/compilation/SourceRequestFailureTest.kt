// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.sources.SourceDocumentDescriptor
import com.github.albertocavalcante.gvy.gls.sources.SourceFailure
import groovy.transform.CompileStatic
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.net.URL
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceRequestFailureTest {
    @Test
    fun `an unreadable known class resource halts lookup instead of certifying its absence`(): Unit = runBlocking {
        val uri = URI.create("file:///source-context/Money.groovy")
        val loader = object : ClassLoader(CompileStatic::class.java.classLoader) {
            override fun getResource(name: String): URL? {
                if (name == "sample/Broken.class") throw IOException("Known class container failed verification")
                return super.getResource(name)
            }
        }
        val compilation = GroovyCompilationService(parentClassLoader = loader)
        try {
            assertTrue(compilation.compile(uri, "class Money { String currency }").isSuccess)
            val original = assertNotNull(compilation.captureSourceRequest(uri, "java.lang.String"))
            val descriptor = SourceDocumentDescriptor(
                "a".repeat(64),
                "b".repeat(64),
                "java/lang/String.java",
                Path.of("String.java").toAbsolutePath(),
                original.symbol.origin,
                original.context.identity,
                "JDK",
                "java",
            )
            val session = assertNotNull(compilation.captureSourceLookupSession(descriptor))

            assertEquals(
                SourceFailure.IO_FAILURE,
                assertIs<SourceRequestSelection.Failed>(session.select("sample.Broken")).failure,
            )
            val error = assertFailsWith<ResponseErrorException> {
                compilation.captureSourceRequest(uri, "sample.Broken")
            }
            assertEquals(ResponseErrorCode.RequestFailed.value, error.responseError.code)
        } finally {
            compilation.shutdown()
        }
    }
}
