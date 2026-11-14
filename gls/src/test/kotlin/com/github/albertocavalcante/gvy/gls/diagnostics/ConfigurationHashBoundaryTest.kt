// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.diagnostics

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ConfigurationHashBoundaryTest {
    private val uri = URI.create("file:///workspace/.codenarc")

    @Test
    fun `configuration reads retain cancellation`() {
        assertFailsWith<CancellationException> {
            diskConfigurationHash(uri) { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `configuration reads retain fatal errors`() {
        assertFailsWith<AssertionError> { diskConfigurationHash(uri) { throw AssertionError("fatal") } }
    }

    @Test
    fun `unavailable configuration is ignored`() {
        assertNull(diskConfigurationHash(uri) { throw IOException("unavailable") })
    }
}
