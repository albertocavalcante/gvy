// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins

import ch.qos.logback.classic.LoggerContext
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Proves that Jenkins tests run with the shared quiet Logback policy. The policy's behaviour is verified once, in
 * build-tool's LoggingProviderTest, because both modules load the same `tools/test-logging` resource.
 */
class LoggingProviderTest {
    @Test
    fun `test logging uses the shared quiet Logback policy`() {
        val context = assertIs<LoggerContext>(LoggerFactory.getILoggerFactory())
        assertEquals("quiet-v1", context.getProperty("gvy.test.logging.policy"))
    }
}
