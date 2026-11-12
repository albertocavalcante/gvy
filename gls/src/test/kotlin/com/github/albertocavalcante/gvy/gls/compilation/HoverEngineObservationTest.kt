// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.gvy.gls.engine.config.EngineConfiguration
import com.github.albertocavalcante.gvy.gls.engine.config.EngineType
import com.github.albertocavalcante.gvy.gls.services.DocumentProvider
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HoverEngineObservationTest {
    @Test
    fun `observation does not create engine and distinguishes actual instance from configuration`() {
        val manager = LanguageEngineManager(
            compilationService = mockk(relaxed = true),
            parser = GroovyParserFacade(),
            parentClassLoader = ClassLoader.getPlatformClassLoader(),
            workspaceManager = WorkspaceManager(),
            options = LanguageEngineManagerOptions(
                documentProvider = DocumentProvider(),
                engineConfig = EngineConfiguration(EngineType.Core),
            ),
        )
        val before = manager.observeHoverEngine()
        assertEquals("core", before.configured)
        assertEquals("not_instantiated", before.state)
        assertNull(before.instantiated)
        assertEquals(before, manager.observeHoverEngine())
        assertEquals("core", manager.activeEngine.id)
        assertEquals("core", manager.observeHoverEngine().instantiated)
        manager.updateEngineConfiguration(EngineConfiguration(EngineType.OpenRewrite))
        val reset = manager.observeHoverEngine()
        assertEquals("openrewrite", reset.configured)
        assertEquals("not_instantiated", reset.state)
        assertNull(reset.instantiated)
        assertEquals("rewrite", manager.activeEngine.id)
        assertEquals("rewrite", manager.observeHoverEngine().instantiated)
    }
}
