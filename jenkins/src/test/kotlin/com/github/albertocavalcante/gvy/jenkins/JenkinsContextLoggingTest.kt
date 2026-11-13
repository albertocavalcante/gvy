// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.github.albertocavalcante.gvy.jenkins.metadata.BundledJenkinsMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.JenkinsStepMetadata
import com.github.albertocavalcante.gvy.jenkins.metadata.MergedJenkinsMetadata
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Isolated("Temporarily attaches an owned appender to the Jenkins loggers")
class JenkinsContextLoggingTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `an absent optional Maven repository keeps known classpath and metadata without warnings`() {
        var lookups = 0
        val fixture = fixture {
            lookups++
            null
        }

        val captured = captureLogs { fixture.build(emptyList()) }

        assertKnownClasspathAndMetadata(fixture.jar, captured.result)
        assertEquals(1, lookups)
        val absence = captured.events.filter { it.message == "Could not determine Maven repository location" }
        assertEquals(listOf(Level.DEBUG), absence.map { it.level }, "optional repository absence remains diagnostic")
        assertTrue(captured.events.none { it.level.isGreaterOrEqual(Level.WARN) })
    }

    @Test
    fun `a missing library reference emits one resolver warning and keeps the known library`() {
        val fixture = fixture { null }
        val references = listOf(LibraryReference("known"), LibraryReference("missing"))

        val captured = captureLogs { fixture.build(references) }

        assertKnownClasspathAndMetadata(fixture.jar, captured.result)
        val warnings = captured.events.filter { it.level.isGreaterOrEqual(Level.WARN) }
        val missingWarnings = warnings.filter { "'missing'" in it.message }
        assertEquals(1, missingWarnings.size, "missing reference produces one resolver warning")
        assertEquals(missingWarnings, warnings, "known library resolution produces no unrelated warnings")
        assertEquals(SharedLibraryResolver::class.java.name, warnings.single().loggerName)
        assertEquals(Level.WARN, warnings.single().level)
        assertEquals("Jenkins shared library 'missing' referenced but not configured", warnings.single().message)
    }

    @Test
    fun `a Maven lookup failure remains a warning and keeps known classpath and metadata`() {
        val fixture = fixture { throw IOException("controlled repository lookup failure") }

        val captured = captureLogs { fixture.build(emptyList()) }

        assertKnownClasspathAndMetadata(fixture.jar, captured.result)
        val warnings = captured.events.filter { it.level.isGreaterOrEqual(Level.WARN) }
        assertEquals(1, warnings.size)
        assertEquals(JenkinsContext::class.java.name, warnings.single().loggerName)
        assertEquals(Level.WARN, warnings.single().level)
        assertEquals("Failed to lookup local jenkins-core", warnings.single().message)
        assertEquals(IOException::class.java.name, warnings.single().throwableClassName)
    }

    private fun fixture(lookup: () -> Path?): Fixture {
        val jar = Files.createFile(tempDir.resolve("known.jar"))
        val base = MergedJenkinsMetadata(
            jenkinsVersion = "test",
            steps = emptyMap(),
            globalVariables = emptyMap(),
            sections = emptyMap(),
            directives = emptyMap(),
        )
        val context = JenkinsContext(
            configuration = JenkinsConfiguration(sharedLibraries = listOf(SharedLibrary("known", jar.toString()))),
            workspaceRoot = tempDir,
            pluginManager = JenkinsPluginManager(),
            bundledMetadataLoader = { base },
            classpathScanner = { classpath ->
                assertEquals(listOf(jar), classpath)
                BundledJenkinsMetadata(
                    steps = mapOf(
                        "knownStep" to JenkinsStepMetadata(
                            name = "knownStep",
                            plugin = "test",
                            parameters = emptyMap(),
                        ),
                    ),
                    globalVariables = emptyMap(),
                )
            },
            stubHooks = StubHooks(cacheRoot = tempDir.resolve("stubs")),
            mavenRepositoryLookupOverride = lookup,
        )
        return Fixture(context, jar)
    }

    private class Fixture(val context: JenkinsContext, val jar: Path) {
        fun build(references: List<LibraryReference>): BuildResult {
            val classpath = context.buildScopedClasspath(references)
            return BuildResult(classpath, context.getAllMetadata(classpath.scanKey))
        }
    }

    private class BuildResult(val classpath: ScopedClasspath, val metadata: MergedJenkinsMetadata)

    private fun assertKnownClasspathAndMetadata(jar: Path, result: BuildResult) {
        assertEquals(listOf(jar), result.classpath.scanKey)
        assertTrue(jar in result.classpath.entries)
        val stubs = result.classpath.entries.filter { Files.isDirectory(it) }
        assertTrue(stubs.isNotEmpty())
        assertTrue(stubs.all { it.startsWith(tempDir.resolve("stubs")) })
        assertEquals(setOf("knownStep"), result.metadata.steps.keys)
        assertEquals("test", result.metadata.steps.getValue("knownStep").plugin)
    }

    private data class Event(
        val level: Level,
        val loggerName: String,
        val message: String,
        val throwableClassName: String?,
    )

    private class BoundedAppender : AppenderBase<ILoggingEvent>() {
        private val events = mutableListOf<Event>()
        private val ownerThread = Thread.currentThread()
        var overflow = false
            private set
        var unexpectedThread = false
            private set

        @Synchronized
        override fun append(event: ILoggingEvent) {
            unexpectedThread = unexpectedThread || Thread.currentThread() !== ownerThread
            val message = event.formattedMessage
            val loggerName = event.loggerName
            val throwableClass = event.throwableProxy?.className
            if (events.size == MAX_EVENTS) {
                overflow = true
                return
            }
            val exceedsFieldBound =
                message.length > MAX_MESSAGE_CHARS ||
                    loggerName.length > MAX_NAME_CHARS ||
                    (throwableClass?.length ?: 0) > MAX_NAME_CHARS
            if (exceedsFieldBound) {
                overflow = true
            }
            events += Event(
                event.level,
                loggerName.take(MAX_NAME_CHARS),
                message.take(MAX_MESSAGE_CHARS),
                throwableClass?.take(MAX_NAME_CHARS),
            )
        }

        @Synchronized
        fun snapshot(): List<Event> = events.toList()
    }

    private class LoggerState(val logger: Logger) {
        private val level = logger.level
        private val additive = logger.isAdditive
        private val appenders = logger.iteratorForAppenders().asSequence().toList()

        fun restore() {
            logger.level = level
            logger.isAdditive = additive
        }

        fun assertRestored() {
            assertEquals(level, logger.level)
            assertEquals(additive, logger.isAdditive)
            val current = logger.iteratorForAppenders().asSequence().toList()
            assertEquals(appenders.size, current.size)
            appenders.zip(current).forEach { (before, after) -> assertSame(before, after) }
        }
    }

    private class Captured<T>(val result: T, val events: List<Event>)

    private fun <T> captureLogs(action: () -> T): Captured<T> {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val loggers = listOf(
            context.getLogger(JenkinsContext::class.java.name),
            context.getLogger(SharedLibraryResolver::class.java.name),
        )
        val states = loggers.map(::LoggerState)
        val rootState = LoggerState(context.getLogger(Logger.ROOT_LOGGER_NAME))
        val originalMdc = MDC.getCopyOfContextMap()
        val originalInput = System.`in`
        val originalOutput = System.out
        val originalError = System.err
        val appender = BoundedAppender()
        appender.context = context
        appender.start()
        try {
            loggers.forEach { logger ->
                logger.level = Level.DEBUG
                logger.isAdditive = false
                logger.addAppender(appender)
            }
            val result = action()
            return Captured(result, appender.snapshot())
        } finally {
            loggers.forEach { it.detachAppender(appender) }
            states.forEach { it.restore() }
            appender.stop()
            states.forEach { it.assertRestored() }
            rootState.assertRestored()
            assertEquals(originalMdc, MDC.getCopyOfContextMap())
            assertSame(originalInput, System.`in`)
            assertSame(originalOutput, System.out)
            assertSame(originalError, System.err)
            assertFalse(appender.isStarted)
            assertFalse(appender.overflow, "owned appender exceeded its retention bounds")
            assertFalse(appender.unexpectedThread, "classpath build emitted asynchronously")
        }
    }

    companion object {
        private const val MAX_EVENTS = 64
        private const val MAX_MESSAGE_CHARS = 1024
        private const val MAX_NAME_CHARS = 256
    }
}
