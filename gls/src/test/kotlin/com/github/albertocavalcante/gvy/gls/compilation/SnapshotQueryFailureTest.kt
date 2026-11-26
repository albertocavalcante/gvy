// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class SnapshotQueryFailureTest {
    enum class Stage { BEFORE, COMPUTE, AFTER }

    @ParameterizedTest
    @EnumSource(Stage::class)
    fun `cancellation inside a throwing supplier or computation wins at the query boundary`(stage: Stage): Unit =
        runBlocking {
            var escaped: Throwable? = null
            launch {
                fun failAt(point: Stage) {
                    if (point == stage) {
                        cancel(CancellationException("caller cancelled at $point"))
                        throw IOException("failed at $point")
                    }
                }
                var first = true
                escaped = runCatching {
                    queryCurrentSnapshot({
                        failAt(if (first) Stage.BEFORE else Stage.AFTER)
                        first = false
                        true
                    }) {
                        failAt(Stage.COMPUTE)
                    }
                }.exceptionOrNull()
            }.join()
            // Observe the exception directly at query return. A cancelled Job can mask an escaped IOException.
            assertIs<CancellationException>(escaped)
        }

    @Test
    fun `retirement discards a computation failure belonging to the previous source`(): Unit = runBlocking {
        var current = true
        val outcome = queryCurrentSnapshot({ current }) {
            current = false
            throw IOException("failed on the retired source")
        }
        assertIs<SnapshotQuery.Stale>(outcome)
    }

    @Test
    fun `a computation failure on current inputs retains its identity`(): Unit = runBlocking {
        val failure = IOException("current failure")
        assertSame(failure, assertFailsWith<IOException> { queryCurrentSnapshot({ true }) { throw failure } })
    }

    @Test
    fun `a fatal error retains its identity even when inputs retire`(): Unit = runBlocking {
        val fatal = AssertionError("fatal")
        var current = true
        val escaped = assertFailsWith<AssertionError> {
            queryCurrentSnapshot({ current }) {
                current = false
                throw fatal
            }
        }
        assertSame(fatal, escaped)
    }
}
