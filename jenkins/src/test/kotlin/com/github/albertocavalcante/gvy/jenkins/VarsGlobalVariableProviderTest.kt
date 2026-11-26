// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class VarsGlobalVariableProviderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `a directory companion is skipped before attempting to read its contents`() {
        val vars = Files.createDirectory(tempDir.resolve("vars"))
        Files.writeString(vars.resolve("step.groovy"), "def call() {}")
        Files.createDirectory(vars.resolve("step.txt"))
        val logger = LoggerFactory.getLogger(VarsGlobalVariableProvider::class.java) as Logger
        val original = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            logger.level = Level.WARN
            val step = VarsGlobalVariableProvider(tempDir).getGlobalVariables().single()
            assertEquals("", step.documentation)
            assertTrue(appender.list.none { it.formattedMessage.contains("Failed to read documentation for step") })
        } finally {
            logger.level = original
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `should scan vars directory and detect global variables`() {
        val varsDir = tempDir.resolve("vars")
        Files.createDirectory(varsDir)

        val logScript = varsDir.resolve("log.groovy")
        Files.writeString(logScript, "def info(msg) { println msg }")

        val utilsScript = varsDir.resolve("Utils.groovy")
        Files.writeString(utilsScript, "class Utils {}")

        val provider = VarsGlobalVariableProvider(tempDir)
        val vars = provider.getGlobalVariables()

        assertEquals(2, vars.size)
        assertTrue(vars.any { it.name == "log" })
        assertTrue(vars.any { it.name == "Utils" })
    }

    @Test
    fun `should return variables in deterministic name order`() {
        val varsDir = tempDir.resolve("vars")
        Files.createDirectory(varsDir)

        Files.writeString(varsDir.resolve("b.groovy"), "def call() { }")
        Files.writeString(varsDir.resolve("A.groovy"), "def call() { }")
        Files.writeString(varsDir.resolve("c.groovy"), "def call() { }")

        val provider = VarsGlobalVariableProvider(tempDir)
        val vars = provider.getGlobalVariables()

        assertEquals(listOf("A", "b", "c"), vars.map { it.name })
    }
}
