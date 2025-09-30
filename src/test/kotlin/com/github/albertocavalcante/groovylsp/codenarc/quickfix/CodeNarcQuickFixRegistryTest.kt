package com.github.albertocavalcante.groovylsp.codenarc.quickfix

import com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers.CodeNarcQuickFixer
import com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers.FixContext
import com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers.FixerCategory
import com.github.albertocavalcante.groovylsp.codenarc.quickfix.fixers.FixerMetadata
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CodeNarcQuickFixRegistryTest {

    private lateinit var registry: CodeNarcQuickFixRegistry

    @BeforeEach
    fun setUp() {
        registry = CodeNarcQuickFixRegistry()
    }

    @Test
    fun `should register and retrieve fixers by rule name`() {
        val fixer = createTestFixer("TestRule")

        registry.register(fixer)

        val fixers = registry.getFixers("TestRule")
        assertEquals(1, fixers.size)
        assertEquals(fixer, fixers.first())
    }

    @Test
    fun `should return empty list for unknown rule`() {
        val fixers = registry.getFixers("UnknownRule")

        assertTrue(fixers.isEmpty())
    }

    @Test
    fun `should register multiple fixers for same rule`() {
        val fixer1 = createTestFixer("TestRule", priority = 1)
        val fixer2 = createTestFixer("TestRule", priority = 2)

        registry.register(fixer1)
        registry.register(fixer2)

        val fixers = registry.getFixers("TestRule")
        assertEquals(2, fixers.size)
        // Should be ordered by priority (1 = highest priority)
        assertEquals(fixer1, fixers[0])
        assertEquals(fixer2, fixers[1])
    }

    @Test
    fun `should return fixers ordered by priority`() {
        val lowPriority = createTestFixer("TestRule", priority = 5)
        val highPriority = createTestFixer("TestRule", priority = 1)
        val mediumPriority = createTestFixer("TestRule", priority = 3)

        // Register in random order
        registry.register(lowPriority)
        registry.register(highPriority)
        registry.register(mediumPriority)

        val fixers = registry.getFixers("TestRule")
        assertEquals(3, fixers.size)
        assertEquals(highPriority, fixers[0])
        assertEquals(mediumPriority, fixers[1])
        assertEquals(lowPriority, fixers[2])
    }

    @Test
    fun `should get all supported rules`() {
        registry.register(createTestFixer("Rule1"))
        registry.register(createTestFixer("Rule2"))
        registry.register(createTestFixer("Rule1")) // Duplicate rule

        val supportedRules = registry.getSupportedRules()
        assertEquals(setOf("Rule1", "Rule2"), supportedRules)
    }

    @Test
    fun `should get fix-all candidates`() {
        val formatting = createTestFixer("FormattingRule", category = FixerCategory.FORMATTING)
        val imports = createTestFixer("ImportRule", category = FixerCategory.IMPORTS)
        val security = createTestFixer("SecurityRule", category = FixerCategory.SECURITY)

        registry.register(formatting)
        registry.register(imports)
        registry.register(security)

        val candidates = registry.getFixAllCandidates()
        assertEquals(3, candidates.size)
        assertTrue(candidates.contains(formatting))
        assertTrue(candidates.contains(imports))
        assertTrue(candidates.contains(security))
    }

    @Test
    fun `should prevent registering fixer with invalid metadata`() {
        assertThrows(IllegalArgumentException::class.java) {
            createTestFixer("", priority = 1) // Empty rule name
        }

        assertThrows(IllegalArgumentException::class.java) {
            createTestFixer("TestRule", priority = 0) // Invalid priority
        }

        assertThrows(IllegalArgumentException::class.java) {
            createTestFixer("TestRule", priority = 11) // Invalid priority
        }
    }

    @Test
    fun `should get metadata for registered fixers`() {
        val fixer = createTestFixer("TestRule")
        registry.register(fixer)

        val metadata = registry.getFixerMetadata("TestRule")
        assertEquals(1, metadata.size)
        assertEquals(fixer.metadata, metadata.first())
    }

    private fun createTestFixer(
        ruleName: String,
        priority: Int = 5,
        category: FixerCategory = FixerCategory.FORMATTING,
        isPreferred: Boolean = false,
    ): CodeNarcQuickFixer {
        return object : CodeNarcQuickFixer {
            override val metadata = FixerMetadata(
                ruleName = ruleName,
                category = category,
                priority = priority,
                isPreferred = isPreferred,
            )

            override fun canFix(diagnostic: Diagnostic, context: FixContext): Boolean = diagnostic.code.left == ruleName

            override fun computeAction(diagnostic: Diagnostic, context: FixContext): CodeAction? {
                if (!canFix(diagnostic, context)) return null
                return CodeAction().apply {
                    title = "Fix $ruleName"
                    kind = CodeActionKind.QuickFix
                    diagnostics = listOf(diagnostic)
                }
            }

            override fun computeFixAllAction(diagnostics: List<Diagnostic>, context: FixContext): CodeAction? {
                val relevantDiagnostics = diagnostics.filter { canFix(it, context) }
                if (relevantDiagnostics.isEmpty()) return null

                return CodeAction().apply {
                    title = "Fix all $ruleName issues"
                    kind = CodeActionKind.SourceFixAll
                    this.diagnostics = relevantDiagnostics
                }
            }
        }
    }
}
