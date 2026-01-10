package com.github.albertocavalcante.groovylsp.providers.diagnostics

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import com.github.albertocavalcante.groovylsp.providers.semantictokens.GroovySemanticTokenProvider
import com.github.albertocavalcante.groovylsp.providers.semantictokens.JenkinsSemanticTokenProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ModuleNode
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DiagnosticTag
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end integration tests for unused import dimming feature.
 *
 * Tests the complete flow:
 * 1. TypeUsageCollector collects used types from AST
 * 2. UnusedImportDetector identifies unused imports
 * 3. UnusedImportDiagnosticProvider generates diagnostics with DiagnosticTag.Unnecessary
 * 4. GroovySemanticTokenProvider marks tokens with UNNECESSARY modifier
 *
 * This ensures both visual feedback mechanisms work together:
 * - Strikethrough from DiagnosticTag.Unnecessary
 * - Dimming from semantic token "unnecessary" modifier
 */
class UnusedImportDimmingIntegrationTest {

    private lateinit var compilationService: GroovyCompilationService
    private lateinit var diagnosticProvider: UnusedImportDiagnosticProvider
    private val uri = URI.create("file:///Test.groovy")

    @BeforeEach
    fun setup() {
        compilationService = GroovyCompilationService()
        diagnosticProvider = UnusedImportDiagnosticProvider(compilationService)
    }

    private fun compile(code: String) = runBlocking {
        compilationService.compile(uri, code)
    }

    @Test
    fun `full flow - unused import gets both diagnostic and semantic token marker`() = runBlocking {
        val code = """
            import java.util.ArrayList
            import java.util.HashMap
            import java.util.LinkedList

            ArrayList list = new ArrayList()
            LinkedList other = new LinkedList()
        """.trimIndent()

        compile(code)

        // 1. Verify diagnostics
        val diagnostics = diagnosticProvider.provideDiagnostics(uri, code).toList()
        assertEquals(1, diagnostics.size, "Should detect exactly one unused import (HashMap)")

        val diagnostic = diagnostics.first()
        assertTrue(diagnostic.tags.contains(DiagnosticTag.Unnecessary), "Diagnostic should have Unnecessary tag")
        assertTrue(diagnostic.message.contains("HashMap"), "Diagnostic message should mention HashMap")
        assertEquals(DiagnosticSeverity.Hint, diagnostic.severity, "Severity should be Hint")
        assertEquals("unused-import", diagnostic.code.left, "Diagnostic code should be 'unused-import'")

        // 2. Verify semantic tokens
        val ast = compilationService.getAst(uri) as ModuleNode
        val astModel = compilationService.getAstModel(uri)!!
        val unusedImports = UnusedImportDetector.detectUnusedImports(ast).toSet()

        val tokens = GroovySemanticTokenProvider.getSemanticTokens(
            astModel,
            uri,
            unusedImports = unusedImports,
            moduleNode = ast,
        )

        // HashMap is on line 1 (0-indexed)
        val hashMapToken = tokens.find {
            it.line == 1 && it.tokenType == GroovySemanticTokenProvider.TokenTypes.CLASS
        }

        assertNotNull(hashMapToken, "Should have token for HashMap import")
        val hasUnnecessary = (hashMapToken.tokenModifiers and GroovySemanticTokenProvider.TokenModifiers.UNNECESSARY) != 0
        assertTrue(hasUnnecessary, "HashMap token should have UNNECESSARY modifier")

        // ArrayList (line 0) should NOT have unnecessary modifier
        val arrayListToken = tokens.find {
            it.line == 0 && it.tokenType == GroovySemanticTokenProvider.TokenTypes.CLASS
        }
        assertNotNull(arrayListToken, "Should have token for ArrayList import")
        val arrayListUnnecessary = (arrayListToken.tokenModifiers and GroovySemanticTokenProvider.TokenModifiers.UNNECESSARY) != 0
        assertFalse(arrayListUnnecessary, "ArrayList token should NOT have UNNECESSARY modifier")
    }

    @Test
    fun `aliased imports are correctly detected as used or unused`() = runBlocking {
        val code = """
            import java.util.ArrayList as AL
            import java.util.HashMap as HM

            AL list = new AL()
        """.trimIndent()

        compile(code)

        val diagnostics = diagnosticProvider.provideDiagnostics(uri, code).toList()

        assertEquals(1, diagnostics.size, "Should detect HM (HashMap) as unused")
        assertTrue(
            diagnostics.any { it.message.contains("HashMap") },
            "Should detect HashMap (aliased as HM) as unused",
        )
    }

    @Test
    fun `static imports are correctly detected`() = runBlocking {
        val code = """
            import static java.util.Collections.emptyList
            import static java.util.Collections.emptyMap

            def list = emptyList()
        """.trimIndent()

        compile(code)

        val diagnostics = diagnosticProvider.provideDiagnostics(uri, code).toList()

        assertEquals(1, diagnostics.size, "Should detect emptyMap as unused")
        assertTrue(
            diagnostics.any { it.message.contains("emptyMap") },
            "Should detect emptyMap static import as unused",
        )
    }

    @Test
    fun `star imports are not reported as unused`() = runBlocking {
        val code = """
            import java.util.*

            def x = 1
        """.trimIndent()

        compile(code)

        val diagnostics = diagnosticProvider.provideDiagnostics(uri, code).toList()

        assertTrue(diagnostics.isEmpty(), "Star imports should not be reported as unused")
    }

    @Test
    fun `imports used only in annotations are detected as used`() = runBlocking {
        val code = """
            import groovy.transform.ToString
            import java.util.HashMap

            @ToString
            class Data {}
        """.trimIndent()

        compile(code)

        val diagnostics = diagnosticProvider.provideDiagnostics(uri, code).toList()

        assertEquals(1, diagnostics.size, "Should detect exactly one unused import")
        assertTrue(
            diagnostics.any { it.message.contains("HashMap") },
            "HashMap should be unused (ToString is used in annotation)",
        )
    }

    @Test
    fun `imports used in extends clause are detected as used`() = runBlocking {
        val code = """
            import java.util.AbstractList
            import java.util.HashMap

            abstract class MyList extends AbstractList {
                Object get(int i) { null }
                int size() { 0 }
            }
        """.trimIndent()

        compile(code)

        val diagnostics = diagnosticProvider.provideDiagnostics(uri, code).toList()

        assertEquals(1, diagnostics.size)
        assertTrue(
            diagnostics.any { it.message.contains("HashMap") },
            "HashMap should be unused (AbstractList is used in extends)",
        )
    }

    @Test
    fun `imports used in generic type arguments are detected as used`() = runBlocking {
        val code = """
            import java.util.List
            import java.util.Map
            import java.util.Set

            List<Map> nestedList = []
        """.trimIndent()

        compile(code)

        val diagnostics = diagnosticProvider.provideDiagnostics(uri, code).toList()

        assertEquals(1, diagnostics.size, "Only Set should be unused")
        assertTrue(
            diagnostics.any { it.message.contains("Set") },
            "Set should be unused (List and Map are used in generic)",
        )
    }

    @Test
    fun `unnecessary modifier legend is correctly configured`() {
        val modifiers = JenkinsSemanticTokenProvider.LEGEND_TOKEN_MODIFIERS
        val index = modifiers.indexOf("unnecessary")

        assertTrue(index >= 0, "'unnecessary' should be in semantic token modifiers legend")
        assertTrue(index < 32, "Index should be valid for bitmask (< 32)")

        val expectedMask = 1 shl index
        assertEquals(
            expectedMask,
            GroovySemanticTokenProvider.TokenModifiers.UNNECESSARY,
            "UNNECESSARY constant should match computed bitmask",
        )
    }
}
