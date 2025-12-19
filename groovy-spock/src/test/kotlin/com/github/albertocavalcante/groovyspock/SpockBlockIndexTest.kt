package com.github.albertocavalcante.groovyspock

import com.github.albertocavalcante.groovyparser.GroovyParserFacade
import com.github.albertocavalcante.groovyparser.api.ParseRequest
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpockBlockIndexTest {

    private val parser = GroovyParserFacade()

    private fun parseAndGetMethod(code: String, methodNameContains: String = "feature"): MethodNode? {
        val result = parser.parse(
            ParseRequest(
                uri = URI.create("file:///FooSpec.groovy"),
                content = code,
                // Use CONVERSION phase to preserve statement labels (labels are transformed at later phases)
                compilePhase = org.codehaus.groovy.control.Phases.CONVERSION,
            ),
        )
        val module = result.ast as? ModuleNode ?: return null
        // Spock feature methods with string names are parsed with the literal string as the name
        // e.g. def "feature method"() { } has name = "feature method"
        return module.classes
            .flatMap { it.methods }
            .find { it.name.contains(methodNameContains, ignoreCase = true) }
    }

    @Test
    fun `build detects simple given-when-then sequence`() {
        val code = """
            import spock.lang.Specification
            
            class FooSpec extends Specification {
                def "feature method"() {
                    given: "setup"
                    def x = 1
                    
                    when: "action"
                    x++
                    
                    then: "assertion"
                    x == 2
                }
            }
        """.trimIndent()

        val method = parseAndGetMethod(code)
        assertNotNull(method, "Method should be parsed")

        val index = SpockBlockIndex.build(method)
        assertEquals(3, index.blocks.size, "Should have 3 blocks")

        assertEquals(SpockBlock.GIVEN, index.blocks[0].block)
        assertEquals(SpockBlock.WHEN, index.blocks[1].block)
        assertEquals(SpockBlock.THEN, index.blocks[2].block)
    }

    @Test
    fun `build detects expect block`() {
        val code = """
            import spock.lang.Specification
            
            class FooSpec extends Specification {
                def "feature method"() {
                    expect: "simple assertion"
                    1 + 1 == 2
                }
            }
        """.trimIndent()

        val method = parseAndGetMethod(code)
        assertNotNull(method)

        val index = SpockBlockIndex.build(method)
        assertEquals(1, index.blocks.size)
        assertEquals(SpockBlock.EXPECT, index.blocks[0].block)
    }

    @Test
    fun `build handles and block continuation`() {
        val code = """
            import spock.lang.Specification
            
            class FooSpec extends Specification {
                def "feature method"() {
                    given: "setup"
                    def x = 1
                    
                    and: "more setup"
                    def y = 2
                    
                    when: "action"
                    x++
                    
                    then: "assertion"
                    x == 2
                    
                    and: "additional assertion"
                    y == 2
                }
            }
        """.trimIndent()

        val method = parseAndGetMethod(code)
        assertNotNull(method)

        val index = SpockBlockIndex.build(method)
        assertEquals(5, index.blocks.size)

        // First AND continues GIVEN
        val andAfterGiven = index.blocks[1]
        assertEquals(SpockBlock.AND, andAfterGiven.block)
        assertEquals(SpockBlock.GIVEN, andAfterGiven.continues)

        // Second AND continues THEN
        val andAfterThen = index.blocks[4]
        assertEquals(SpockBlock.AND, andAfterThen.block)
        assertEquals(SpockBlock.THEN, andAfterThen.continues)
    }

    @Test
    fun `build handles where block`() {
        val code = """
            import spock.lang.Specification
            
            class FooSpec extends Specification {
                def "feature method"() {
                    expect: "data driven"
                    a + b == c
                    
                    where:
                    a | b || c
                    1 | 2 || 3
                    4 | 5 || 9
                }
            }
        """.trimIndent()

        val method = parseAndGetMethod(code)
        assertNotNull(method)

        val index = SpockBlockIndex.build(method)
        assertTrue(index.blocks.any { it.block == SpockBlock.WHERE }, "Should detect WHERE block")
    }

    @Test
    fun `blockAt returns correct block for line`() {
        val blocks = listOf(
            BlockSpan(SpockBlock.GIVEN, 5, 7),
            BlockSpan(SpockBlock.WHEN, 9, 11),
            BlockSpan(SpockBlock.THEN, 13, 15),
        )
        val index = SpockBlockIndex("test", blocks)

        assertEquals(SpockBlock.GIVEN, index.blockAt(5)?.block)
        assertEquals(SpockBlock.GIVEN, index.blockAt(6)?.block)
        assertEquals(SpockBlock.GIVEN, index.blockAt(7)?.block)
        assertEquals(SpockBlock.WHEN, index.blockAt(9)?.block)
        assertEquals(SpockBlock.THEN, index.blockAt(14)?.block)
        assertNull(index.blockAt(8), "Line between blocks")
        assertNull(index.blockAt(1), "Line before first block")
    }

    @Test
    fun `effectiveBlockAt resolves AND to its continued block`() {
        val blocks = listOf(
            BlockSpan(SpockBlock.THEN, 10, 12),
            BlockSpan(SpockBlock.AND, 13, 15, continues = SpockBlock.THEN),
        )
        val index = SpockBlockIndex("test", blocks)

        assertEquals(SpockBlock.THEN, index.effectiveBlockAt(11))
        assertEquals(SpockBlock.THEN, index.effectiveBlockAt(14)) // AND resolves to THEN
    }

    @Test
    fun `validNextBlocks returns correct options`() {
        // Empty method - can start with given, setup, when, or expect
        val emptyIndex = SpockBlockIndex("test", emptyList())
        assertTrue(
            emptyIndex.validNextBlocks(
                1,
            ).containsAll(listOf(SpockBlock.GIVEN, SpockBlock.SETUP, SpockBlock.WHEN, SpockBlock.EXPECT)),
        )

        // After GIVEN
        val givenIndex = SpockBlockIndex("test", listOf(BlockSpan(SpockBlock.GIVEN, 1, 5)))
        val afterGiven = givenIndex.validNextBlocks(3)
        assertTrue(afterGiven.contains(SpockBlock.WHEN))
        assertTrue(afterGiven.contains(SpockBlock.AND))

        // After WHEN
        val whenIndex = SpockBlockIndex("test", listOf(BlockSpan(SpockBlock.WHEN, 1, 5)))
        val afterWhen = whenIndex.validNextBlocks(3)
        assertTrue(afterWhen.contains(SpockBlock.THEN))
        assertTrue(afterWhen.contains(SpockBlock.AND))

        // After THEN
        val thenIndex = SpockBlockIndex("test", listOf(BlockSpan(SpockBlock.THEN, 1, 5)))
        val afterThen = thenIndex.validNextBlocks(3)
        assertTrue(afterThen.contains(SpockBlock.AND))
        assertTrue(afterThen.contains(SpockBlock.WHERE))
        assertTrue(afterThen.contains(SpockBlock.CLEANUP))
        assertTrue(afterThen.contains(SpockBlock.WHEN)) // Can start another when-then

        // After CLEANUP
        val cleanupIndex = SpockBlockIndex("test", listOf(BlockSpan(SpockBlock.CLEANUP, 1, 5)))
        assertTrue(cleanupIndex.validNextBlocks(3).isEmpty(), "Nothing after cleanup")
    }

    @Test
    fun `build handles empty method`() {
        val code = """
            import spock.lang.Specification
            
            class FooSpec extends Specification {
                def "feature method"() {
                }
            }
        """.trimIndent()

        val method = parseAndGetMethod(code)
        assertNotNull(method)

        val index = SpockBlockIndex.build(method)
        assertTrue(index.blocks.isEmpty(), "Empty method should have no blocks")
    }
}
