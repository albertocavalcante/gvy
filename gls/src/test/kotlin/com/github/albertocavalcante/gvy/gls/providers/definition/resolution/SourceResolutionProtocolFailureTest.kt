// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import com.github.albertocavalcante.groovyparser.ast.types.Position
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassNode
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class SourceResolutionProtocolFailureTest {
    @Test
    fun `source protocol failures end resolution instead of falling through to another definition`(): Unit =
        runBlocking {
            val context =
                ResolutionContext(ClassNode("example.Type", 0, null), URI.create("file:///Use.groovy"), Position(0, 0))
            for (code in listOf(ResponseErrorCode.ContentModified, ResponseErrorCode.RequestFailed)) {
                val expected = ResponseErrorException(ResponseError(code, "source request failed", null))
                var fellThrough = false
                val pipeline = SymbolResolutionStrategy.pipeline(
                    SymbolResolutionStrategy { throw expected },
                    SymbolResolutionStrategy {
                        fellThrough = true
                        SymbolResolutionStrategy.notApplicable()
                    },
                )

                assertSame(expected, assertFailsWith<ResponseErrorException> { pipeline.resolve(context) })
                assertFalse(fellThrough)
            }
        }
}
