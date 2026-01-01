package com.github.albertocavalcante.groovylsp.engine

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.engine.config.EngineConfiguration
import com.github.albertocavalcante.groovylsp.engine.config.EngineFeatures
import com.github.albertocavalcante.groovylsp.engine.config.EngineType
import com.github.albertocavalcante.groovylsp.engine.impl.core.CoreLanguageEngine
import com.github.albertocavalcante.groovylsp.engine.impl.native.NativeLanguageEngine
import com.github.albertocavalcante.groovylsp.services.DocumentProvider
import com.github.albertocavalcante.groovylsp.sources.SourceNavigator
import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Comprehensive tests for [EngineFactory].
 *
 * Verifies factory creates correct engine types and handles all configurations.
 */
class EngineFactoryTest {

    private val mockParser = mockk<GroovyParserFacade>(relaxed = true)
    private val mockCompilationService = mockk<GroovyCompilationService>(relaxed = true)
    private val mockDocumentProvider = mockk<DocumentProvider>(relaxed = true)
    private val mockSourceNavigator = mockk<SourceNavigator>(relaxed = true)

    @Test
    fun `create returns NativeLanguageEngine for Native configuration`() {
        val config = EngineConfiguration(type = EngineType.Native)

        val engine = EngineFactory.create(
            config = config,
            parser = mockParser,
            compilationService = mockCompilationService,
            documentProvider = mockDocumentProvider,
            sourceNavigator = mockSourceNavigator,
        )

        assertIs<NativeLanguageEngine>(engine)
        assertEquals("native", engine.id)
    }

    @Test
    fun `create returns CoreLanguageEngine for Core configuration`() {
        val config = EngineConfiguration(type = EngineType.Core)

        val engine = EngineFactory.create(
            config = config,
            parser = mockParser,
            compilationService = mockCompilationService,
            documentProvider = mockDocumentProvider,
            sourceNavigator = mockSourceNavigator,
        )

        assertIs<CoreLanguageEngine>(engine)
        assertEquals("core", engine.id)
    }

    @Test
    fun `create throws UnsupportedOperationException for OpenRewrite configuration`() {
        val config = EngineConfiguration(type = EngineType.OpenRewrite)

        val exception = assertThrows<UnsupportedOperationException> {
            EngineFactory.create(
                config = config,
                parser = mockParser,
                compilationService = mockCompilationService,
                documentProvider = mockDocumentProvider,
                sourceNavigator = mockSourceNavigator,
            )
        }

        assertEquals(
            "OpenRewrite engine is not yet implemented. Use 'native' engine or wait for future releases.",
            exception.message,
        )
    }

    @Test
    fun `create works with null sourceNavigator for Native engine`() {
        val config = EngineConfiguration(type = EngineType.Native)

        val engine = EngineFactory.create(
            config = config,
            parser = mockParser,
            compilationService = mockCompilationService,
            documentProvider = mockDocumentProvider,
            sourceNavigator = null,
        )

        assertIs<NativeLanguageEngine>(engine)
    }

    @Test
    fun `create works with null sourceNavigator for Core engine`() {
        val config = EngineConfiguration(type = EngineType.Core)

        val engine = EngineFactory.create(
            config = config,
            parser = mockParser,
            compilationService = mockCompilationService,
            documentProvider = mockDocumentProvider,
            sourceNavigator = null,
        )

        assertIs<CoreLanguageEngine>(engine)
    }

    @Test
    fun `create respects default configuration which is Core`() {
        val config = EngineConfiguration() // Uses default

        val engine = EngineFactory.create(
            config = config,
            parser = mockParser,
            compilationService = mockCompilationService,
            documentProvider = mockDocumentProvider,
            sourceNavigator = null,
        )

        assertIs<CoreLanguageEngine>(engine)
    }

    @Test
    fun `create with custom features does not affect engine type selection`() {
        val customFeatures = EngineFeatures(typeInference = false, flowAnalysis = true)
        val config = EngineConfiguration(type = EngineType.Core, features = customFeatures)

        val engine = EngineFactory.create(
            config = config,
            parser = mockParser,
            compilationService = mockCompilationService,
            documentProvider = mockDocumentProvider,
            sourceNavigator = null,
        )

        assertIs<CoreLanguageEngine>(engine)
    }

    @Test
    fun `all EngineType values have corresponding factory case`() {
        // This test ensures exhaustive coverage of EngineType in factory
        val handledTypes = mutableSetOf<EngineType>()

        for (type in EngineType.entries) {
            val config = EngineConfiguration(type = type)
            try {
                EngineFactory.create(
                    config = config,
                    parser = mockParser,
                    compilationService = mockCompilationService,
                    documentProvider = mockDocumentProvider,
                    sourceNavigator = null,
                )
                handledTypes.add(type)
            } catch (e: UnsupportedOperationException) {
                // OpenRewrite is expected to throw
                handledTypes.add(type)
            }
        }

        assertEquals(EngineType.entries.toSet(), handledTypes, "All EngineType values should be handled by factory")
    }
}
