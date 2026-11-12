// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.typedefinition

import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TypeDefinitionParams
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertTrue

class TypeDefinitionProviderTest {
    private val testUri = URI.create("file:///test.groovy")

    @Test
    fun `typeDefinitions returns empty list when no compilation context`(): Unit = runBlocking {
        val provider = TypeDefinitionProvider(
            semanticResolver = mockk<SemanticTypeResolver>(relaxed = true),
            contextProvider = { _: URI -> null },
        )
        val params = TypeDefinitionParams(TextDocumentIdentifier(testUri.toString()), Position(5, 10))

        assertTrue(provider.typeDefinitions(params).isEmpty())
    }
}
