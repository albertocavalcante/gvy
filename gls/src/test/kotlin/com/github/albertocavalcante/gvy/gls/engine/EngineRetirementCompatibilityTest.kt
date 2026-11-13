// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.engine

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import com.github.albertocavalcante.gvy.gls.config.ServerConfiguration
import com.github.albertocavalcante.gvy.gls.engine.config.EngineConfiguration
import com.github.albertocavalcante.gvy.gls.engine.config.EngineType
import com.github.albertocavalcante.gvy.gls.engine.impl.native.NativeLanguageEngine
import com.github.albertocavalcante.gvy.gls.engine.impl.rewrite.OpenRewriteLanguageEngine
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EngineRetirementCompatibilityTest {
    @ParameterizedTest
    @ValueSource(strings = ["core", "CORE", "cOrE"])
    fun `legacy Core names normalize to Native`(value: String) {
        assertEquals(EngineType.Native, EngineType.fromString(value))
    }

    @ParameterizedTest
    @ValueSource(strings = ["core", "CORE", "cOrE"])
    fun `legacy saved settings configure the Native engine`(value: String) {
        val parsed = ServerConfiguration.fromMap(mapOf("groovy.languageServer.engine" to value))

        assertEquals(EngineType.Native, parsed.parserEngine)
    }

    @Test
    fun `available engines contain Native and OpenRewrite without a Core implementation`() {
        assertEquals(listOf("native", "openrewrite"), EngineType.entries.map { it.id })
    }

    @Test
    fun `factory creates Native for a saved Core configuration`() {
        val parsed = ServerConfiguration.fromMap(mapOf("groovy.languageServer.engine" to "core"))
        val engine = createEngine(parsed.parserEngine)

        assertIs<NativeLanguageEngine>(engine)
        assertEquals("native", engine.id)
    }

    @Test
    fun `Native and OpenRewrite remain explicit supported engines`() {
        val native = createEngine(EngineType.fromString("native"))
        val rewrite = createEngine(EngineType.fromString("openrewrite"))

        assertIs<NativeLanguageEngine>(native)
        assertEquals("native", native.id)
        assertIs<OpenRewriteLanguageEngine>(rewrite)
        assertEquals("rewrite", rewrite.id)
    }

    private fun createEngine(type: EngineType) = EngineFactory.create(
        config = EngineConfiguration(type = type),
        parser = mockk<GroovyParserFacade>(relaxed = true),
        compilationService = mockk<GroovyCompilationService>(relaxed = true),
        documentProvider = mockk<DocumentProvider>(relaxed = true),
        sourceNavigator = null,
    )
}
