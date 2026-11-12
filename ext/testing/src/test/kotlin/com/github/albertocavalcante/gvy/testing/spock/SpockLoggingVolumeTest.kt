// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.testing.spock

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpockLoggingVolumeTest {
    @Test
    fun `repeated detector calls preserve result without info chatter`() {
        val logger = LoggerFactory.getLogger(SpockTestDetector::class.java) as Logger
        val previous = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val detector = SpockTestDetector()
            val node = ClassNode("Ordinary", 1, ClassHelper.OBJECT_TYPE)
            for (level in listOf(Level.INFO, Level.DEBUG)) {
                logger.level = level
                repeat(4) { assertFalse(detector.appliesTo(node, null)) }
                if (level == Level.INFO) {
                    assertTrue(appender.list.isEmpty())
                } else {
                    assertEquals(4, appender.list.size)
                    assertTrue(appender.list.all { it.level == Level.DEBUG && it.formattedMessage.length < 512 })
                }
                appender.list.clear()
            }
        } finally {
            logger.level = previous
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
