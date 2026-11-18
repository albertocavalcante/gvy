// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class CompiledSourceViewReuseTest {
    @Test
    fun `source context is reused while request wrappers remain separate`(): Unit = runTest {
        val service = GroovyCompilationService()
        val uri = URI("file:///SourceView.groovy")
        try {
            service.compile(uri, "def local = 1")
            val first = assertNotNull(service.getCompiledSnapshot(uri))
            val second = assertNotNull(service.getCompiledSnapshot(uri))

            assertNotSame(first, second, "Request facts must not be cached in the source view")
            assertSame(first.jenkinsContext, second.jenkinsContext, "Reuse the source-derived scan")
            assertSame(first.sourceIndex, second.sourceIndex, "Reuse the source coordinate index")
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `equal text after retirement gets a new source view`(): Unit = runTest {
        val service = GroovyCompilationService()
        val uri = URI("file:///RetiredSourceView.groovy")
        val source = "def local = 1"
        try {
            service.compile(uri, source)
            val first = assertNotNull(service.getCompiledSnapshot(uri))

            service.invalidateCache(uri)
            service.compile(uri, source)
            val reopened = assertNotNull(service.getCompiledSnapshot(uri))

            assertEquals(first.sourceText, reopened.sourceText)
            assertNotSame(first.jenkinsContext, reopened.jenkinsContext)
            assertNotSame(first.sourceIndex, reopened.sourceIndex)
            val changedCopy = reopened.copy(sourceText = "def replacement = 2")
            assertEquals("def replacement = 2", changedCopy.jenkinsContext.source)
        } finally {
            service.shutdown()
        }
    }
}
