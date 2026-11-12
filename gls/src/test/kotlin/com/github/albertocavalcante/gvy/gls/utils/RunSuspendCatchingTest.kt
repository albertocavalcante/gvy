// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.utils

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RunSuspendCatchingTest {
    @Test
    fun `ordinary exceptions become failures`() {
        val result = runSuspendCatching { error("boom") }
        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `cancellation is rethrown`() {
        assertFailsWith<CancellationException> { runSuspendCatching { throw CancellationException("cancelled") } }
    }

    @Test
    fun `errors are rethrown, not reported as results`() {
        assertFailsWith<StackOverflowError> { runSuspendCatching { throw StackOverflowError() } }
        assertFailsWith<OutOfMemoryError> { runSuspendCatching { throw OutOfMemoryError() } }
    }
}
