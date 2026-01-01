package com.github.albertocavalcante.groovylsp.config

import com.github.albertocavalcante.groovylsp.engine.config.EngineType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive tests for [ServerConfiguration].
 *
 * Tests configuration parsing, defaults, and edge cases.
 */
class ServerConfigurationDefaultsTest {

    @Test
    fun `default configuration uses Core engine`() {
        val config = ServerConfiguration()
        assertEquals(EngineType.Core, config.parserEngine)
    }

    @Test
    fun `fromMap with null returns default configuration with Core engine`() {
        val config = ServerConfiguration.fromMap(null)
        assertEquals(EngineType.Core, config.parserEngine)
    }

    @Test
    fun `fromMap with empty map uses Core engine via fromString`() {
        val config = ServerConfiguration.fromMap(emptyMap())
        assertEquals(EngineType.Core, config.parserEngine)
    }

    @Test
    fun `fromMap parses native engine explicitly`() {
        val config = ServerConfiguration.fromMap(
            mapOf("groovy.languageServer.engine" to "native"),
        )
        assertEquals(EngineType.Native, config.parserEngine)
    }

    @Test
    fun `fromMap parses core engine explicitly`() {
        val config = ServerConfiguration.fromMap(
            mapOf("groovy.languageServer.engine" to "core"),
        )
        assertEquals(EngineType.Core, config.parserEngine)
    }

    @Test
    fun `fromMap parses openrewrite engine explicitly`() {
        val config = ServerConfiguration.fromMap(
            mapOf("groovy.languageServer.engine" to "openrewrite"),
        )
        assertEquals(EngineType.OpenRewrite, config.parserEngine)
    }

    @Test
    fun `fromMap handles engine case-insensitively`() {
        assertEquals(
            EngineType.Native,
            ServerConfiguration.fromMap(mapOf("groovy.languageServer.engine" to "NATIVE")).parserEngine,
        )
        assertEquals(
            EngineType.Core,
            ServerConfiguration.fromMap(mapOf("groovy.languageServer.engine" to "CORE")).parserEngine,
        )
    }

    @Test
    fun `fromMap returns Core for unknown engine values`() {
        val config = ServerConfiguration.fromMap(
            mapOf("groovy.languageServer.engine" to "unknown_engine"),
        )
        assertEquals(EngineType.Core, config.parserEngine)
    }

    @Test
    fun `fromMap returns Core when engine key is missing`() {
        val config = ServerConfiguration.fromMap(
            mapOf("groovy.some.other.setting" to "value"),
        )
        assertEquals(EngineType.Core, config.parserEngine)
    }

    @Test
    fun `compilation mode defaults to WORKSPACE`() {
        val config = ServerConfiguration()
        assertEquals(ServerConfiguration.CompilationMode.WORKSPACE, config.compilationMode)
    }

    @Test
    fun `fromMap parses compilation mode workspace`() {
        val config = ServerConfiguration.fromMap(
            mapOf("groovy.compilation.mode" to "workspace"),
        )
        assertEquals(ServerConfiguration.CompilationMode.WORKSPACE, config.compilationMode)
    }

    @Test
    fun `fromMap parses compilation mode single-file`() {
        val config = ServerConfiguration.fromMap(
            mapOf("groovy.compilation.mode" to "single-file"),
        )
        assertEquals(ServerConfiguration.CompilationMode.SINGLE_FILE, config.compilationMode)
    }

    @Test
    fun `fromMap parses log level`() {
        val config = ServerConfiguration.fromMap(
            mapOf("groovy.server.logLevel" to "debug"),
        )
        assertEquals(ServerConfiguration.LogLevel.DEBUG, config.logLevel)
    }

    @Test
    fun `log level defaults to INFO`() {
        val config = ServerConfiguration()
        assertEquals(ServerConfiguration.LogLevel.INFO, config.logLevel)
    }

    @Test
    fun `fromMap parses maxNumberOfProblems`() {
        val config = ServerConfiguration.fromMap(
            mapOf("groovy.server.maxNumberOfProblems" to 200),
        )
        assertEquals(200, config.maxNumberOfProblems)
    }

    @Test
    fun `maxNumberOfProblems defaults to 100`() {
        val config = ServerConfiguration()
        assertEquals(100, config.maxNumberOfProblems)
    }

    @Test
    fun `fromMap parses javaHome`() {
        val config = ServerConfiguration.fromMap(
            mapOf("groovy.java.home" to "/path/to/java"),
        )
        assertEquals("/path/to/java", config.javaHome)
    }

    @Test
    fun `javaHome defaults to null`() {
        val config = ServerConfiguration()
        assertNull(config.javaHome)
    }

    @Test
    fun `fromMap parses repl configuration`() {
        val config = ServerConfiguration.fromMap(
            mapOf(
                "groovy.repl.enabled" to false,
                "groovy.repl.maxSessions" to 5,
                "groovy.repl.sessionTimeoutMinutes" to 30,
            ),
        )
        assertEquals(false, config.replEnabled)
        assertEquals(5, config.maxReplSessions)
        assertEquals(30, config.replSessionTimeoutMinutes)
    }

    @Test
    fun `repl defaults are sensible`() {
        val config = ServerConfiguration()
        assertTrue(config.replEnabled)
        assertEquals(10, config.maxReplSessions)
        assertEquals(60, config.replSessionTimeoutMinutes)
    }

    @Test
    fun `fromMap parses codenarc configuration`() {
        val config = ServerConfiguration.fromMap(
            mapOf(
                "groovy.codenarc.enabled" to false,
                "groovy.codenarc.propertiesFile" to "/path/to/codenarc.properties",
                "groovy.codenarc.autoDetect" to false,
            ),
        )
        assertEquals(false, config.codeNarcEnabled)
        assertEquals("/path/to/codenarc.properties", config.codeNarcPropertiesFile)
        assertEquals(false, config.codeNarcAutoDetect)
    }

    @Test
    fun `codenarc defaults are sensible`() {
        val config = ServerConfiguration()
        assertTrue(config.codeNarcEnabled)
        assertNull(config.codeNarcPropertiesFile)
        assertTrue(config.codeNarcAutoDetect)
    }

    @Test
    fun `shouldUseWorkspaceCompilation returns true for WORKSPACE mode`() {
        val config = ServerConfiguration(compilationMode = ServerConfiguration.CompilationMode.WORKSPACE)
        assertTrue(config.shouldUseWorkspaceCompilation())
    }

    @Test
    fun `shouldUseWorkspaceCompilation returns false for SINGLE_FILE mode`() {
        val config = ServerConfiguration(compilationMode = ServerConfiguration.CompilationMode.SINGLE_FILE)
        assertTrue(!config.shouldUseWorkspaceCompilation())
    }

    @Test
    fun `shouldUseIncrementalCompilation returns true when file count exceeds threshold`() {
        val config = ServerConfiguration(incrementalThreshold = 50)
        assertTrue(config.shouldUseIncrementalCompilation(100))
    }

    @Test
    fun `shouldUseIncrementalCompilation returns false when file count is below threshold`() {
        val config = ServerConfiguration(incrementalThreshold = 50)
        assertTrue(!config.shouldUseIncrementalCompilation(25))
    }

    @Test
    fun `isWorkspaceTooLarge returns true when file count exceeds max`() {
        val config = ServerConfiguration(maxWorkspaceFiles = 500)
        assertTrue(config.isWorkspaceTooLarge(1000))
    }

    @Test
    fun `isWorkspaceTooLarge returns false when file count is within limit`() {
        val config = ServerConfiguration(maxWorkspaceFiles = 500)
        assertTrue(!config.isWorkspaceTooLarge(250))
    }

    @Test
    fun `toString contains key configuration values`() {
        val config = ServerConfiguration()
        val str = config.toString()
        assertTrue(str.contains("ServerConfiguration"))
        assertTrue(str.contains("mode="))
        assertTrue(str.contains("repl="))
    }

    @Test
    fun `LogLevel fromString parses all levels`() {
        assertEquals(ServerConfiguration.LogLevel.ERROR, ServerConfiguration.LogLevel.fromString("error"))
        assertEquals(ServerConfiguration.LogLevel.WARN, ServerConfiguration.LogLevel.fromString("warn"))
        assertEquals(ServerConfiguration.LogLevel.INFO, ServerConfiguration.LogLevel.fromString("info"))
        assertEquals(ServerConfiguration.LogLevel.DEBUG, ServerConfiguration.LogLevel.fromString("debug"))
        assertEquals(ServerConfiguration.LogLevel.TRACE, ServerConfiguration.LogLevel.fromString("trace"))
    }

    @Test
    fun `LogLevel fromString defaults to INFO for unknown`() {
        assertEquals(ServerConfiguration.LogLevel.INFO, ServerConfiguration.LogLevel.fromString("unknown"))
        assertEquals(ServerConfiguration.LogLevel.INFO, ServerConfiguration.LogLevel.fromString(null))
    }

    @Test
    fun `LogLevel fromString is case-insensitive`() {
        assertEquals(ServerConfiguration.LogLevel.DEBUG, ServerConfiguration.LogLevel.fromString("DEBUG"))
        assertEquals(ServerConfiguration.LogLevel.DEBUG, ServerConfiguration.LogLevel.fromString("Debug"))
    }

    @Test
    fun `todoPatterns has sensible defaults`() {
        val config = ServerConfiguration()
        assertNotNull(config.todoPatterns["TODO"])
        assertNotNull(config.todoPatterns["FIXME"])
        assertNotNull(config.todoPatterns["BUG"])
    }
}
