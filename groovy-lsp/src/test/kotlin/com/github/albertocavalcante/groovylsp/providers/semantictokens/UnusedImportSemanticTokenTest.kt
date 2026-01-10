package com.github.albertocavalcante.groovylsp.providers.semantictokens

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.providers.diagnostics.UnusedImportDetector
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ModuleNode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for semantic token generation with unused import markers.
 */
class UnusedImportSemanticTokenTest {

    private lateinit var compilationService: GroovyCompilationService
    private val uri = URI.create("file:///Test.groovy")

    @BeforeEach
    fun setup() {
        compilationService = GroovyCompilationService()
    }

    private fun compile(code: String) = runBlocking {
        compilationService.compile(uri, code)
    }

    @Test
    fun `should mark unused import with unnecessary modifier`() = runBlocking {
        val code = """
            import java.util.ArrayList
            import java.util.HashMap

            ArrayList list = new ArrayList()
        """.trimIndent()

        compile(code)

        val ast = compilationService.getAst(uri) as ModuleNode
        val astModel = compilationService.getAstModel(uri)!!
        val unusedImports = UnusedImportDetector.detectUnusedImports(ast).toSet()

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(
            astModel,
            uri,
            unusedImports = unusedImports,
            moduleNode = ast,
        )

        // Find token for HashMap import (line 1, 0-indexed)
        // HashMap is on line 1 (0-indexed), starting after "import java.util."
        val hashMapToken = tokens.find {
            it.line == 1 && it.tokenType == GroovySemanticTokenProvider.TokenTypes.CLASS
        }

        assertNotNull(hashMapToken, "Should have token for HashMap import")
        val hasUnnecessary = (
            hashMapToken.tokenModifiers and
                GroovySemanticTokenProvider.TokenModifiers.UNNECESSARY
            ) != 0
        assertTrue(hasUnnecessary, "Unused import should have UNNECESSARY modifier")
    }

    @Test
    fun `should not mark used import with unnecessary modifier`() = runBlocking {
        val code = """
            import java.util.ArrayList

            ArrayList list = new ArrayList()
        """.trimIndent()

        compile(code)

        val ast = compilationService.getAst(uri) as ModuleNode
        val astModel = compilationService.getAstModel(uri)!!
        val unusedImports = UnusedImportDetector.detectUnusedImports(ast).toSet()

        assertEquals(0, unusedImports.size, "ArrayList should be used, no unused imports")

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(
            astModel,
            uri,
            unusedImports = unusedImports,
            moduleNode = ast,
        )

        // Find token for ArrayList import (line 0)
        val arrayListToken = tokens.find {
            it.line == 0 && it.tokenType == GroovySemanticTokenProvider.TokenTypes.CLASS
        }

        assertNotNull(arrayListToken, "Should have token for ArrayList import")
        val hasUnnecessary = (
            arrayListToken.tokenModifiers and
                GroovySemanticTokenProvider.TokenModifiers.UNNECESSARY
            ) != 0
        assertFalse(hasUnnecessary, "Used import should NOT have UNNECESSARY modifier")
    }

    @Test
    fun `should generate import tokens with CLASS type`() = runBlocking {
        val code = """
            import java.util.ArrayList

            def x = 1
        """.trimIndent()

        compile(code)

        val ast = compilationService.getAst(uri) as ModuleNode
        val astModel = compilationService.getAstModel(uri)!!
        val unusedImports = UnusedImportDetector.detectUnusedImports(ast).toSet()

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(
            astModel,
            uri,
            unusedImports = unusedImports,
            moduleNode = ast,
        )

        // Import type name should be tokenized as CLASS
        val importToken = tokens.find { it.line == 0 }
        assertNotNull(importToken, "Should have token for import on line 0")
        assertEquals(
            GroovySemanticTokenProvider.TokenTypes.CLASS,
            importToken.tokenType,
            "Import token should be CLASS type",
        )
    }

    @Test
    fun `unnecessary modifier should have correct bitmask`() {
        // Verify the UNNECESSARY modifier is properly derived from the legend
        val modifiers = JenkinsSemanticTokenProvider.LEGEND_TOKEN_MODIFIERS
        val index = modifiers.indexOf("unnecessary")
        assertTrue(index >= 0, "unnecessary should be in legend")

        val expectedMask = 1 shl index
        assertEquals(
            expectedMask,
            GroovySemanticTokenProvider.TokenModifiers.UNNECESSARY,
            "UNNECESSARY mask should be 1 << index",
        )
    }
}
