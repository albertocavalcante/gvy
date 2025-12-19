package com.github.albertocavalcante.groovyspock

import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.slf4j.LoggerFactory

/**
 * Represents a span of code within a specific Spock block.
 *
 * @property block The type of Spock block
 * @property startLine Start line of the block (1-indexed, inclusive)
 * @property endLine End line of the block (1-indexed, inclusive)
 * @property continues For AND blocks, the logical block type it continues (e.g., THEN for `and:` after `then:`)
 */
data class BlockSpan(val block: SpockBlock, val startLine: Int, val endLine: Int, val continues: SpockBlock? = null)

/**
 * Index of Spock blocks within a feature method.
 *
 * This class provides efficient lookup for determining which Spock block
 * contains a given line, and for computing which blocks are valid to follow.
 *
 * Example:
 * ```groovy
 * def "feature method"() {
 *     given: "setup"     // GIVEN block starts at line 2
 *     def x = 1
 *     when: "action"     // WHEN block starts at line 4
 *     x++
 *     then: "assertion"  // THEN block starts at line 6
 *     x == 2
 * }
 * ```
 */
class SpockBlockIndex(val methodName: String, val blocks: List<BlockSpan>) {

    /**
     * Find the Spock block containing the given line.
     *
     * @param line 1-indexed line number
     * @return The [BlockSpan] containing the line, or null if not within a block
     */
    fun blockAt(line: Int): BlockSpan? = blocks.find { line >= it.startLine && line <= it.endLine }

    /**
     * Get the effective block type, resolving AND blocks to their continued type.
     */
    fun effectiveBlockAt(line: Int): SpockBlock? {
        val span = blockAt(line) ?: return null
        return if (span.block == SpockBlock.AND) span.continues else span.block
    }

    /**
     * Determine which Spock blocks are valid to appear next based on current position.
     *
     * Follows Spock semantics:
     * - Feature methods start with: given/setup, when, or expect
     * - given/setup → when, and
     * - when → then, and
     * - then → and, where, cleanup, when (for another when-then pair)
     * - expect → and, where, cleanup
     * - where → cleanup
     * - cleanup → (nothing, must be last)
     * - and → same as the block it continues
     */
    fun validNextBlocks(line: Int): List<SpockBlock> {
        // Try current position first, then fall back to last block that ends before this line
        val current = effectiveBlockAt(line) ?: blocks.lastOrNull { it.endLine < line }?.let {
            if (it.block == SpockBlock.AND) it.continues else it.block
        }

        return when (current) {
            null -> listOf(SpockBlock.GIVEN, SpockBlock.SETUP, SpockBlock.WHEN, SpockBlock.EXPECT)
            SpockBlock.GIVEN, SpockBlock.SETUP -> listOf(SpockBlock.WHEN, SpockBlock.AND)
            SpockBlock.WHEN -> listOf(SpockBlock.THEN, SpockBlock.AND)
            SpockBlock.THEN -> listOf(SpockBlock.AND, SpockBlock.WHERE, SpockBlock.CLEANUP, SpockBlock.WHEN)
            SpockBlock.EXPECT -> listOf(SpockBlock.AND, SpockBlock.WHERE, SpockBlock.CLEANUP)
            SpockBlock.WHERE -> listOf(SpockBlock.CLEANUP)
            SpockBlock.CLEANUP -> emptyList()
            SpockBlock.AND -> emptyList() // Should not happen - AND is resolved to continues
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SpockBlockIndex::class.java)

        /**
         * Build a SpockBlockIndex from a feature method's AST.
         *
         * @param method The MethodNode to analyze
         * @return A SpockBlockIndex containing all detected blocks
         */
        fun build(method: MethodNode): SpockBlockIndex {
            val blocks = mutableListOf<BlockSpan>()
            val code = method.code

            if (code !is BlockStatement) {
                logger.debug("Method {} has no block statement body", method.name)
                return SpockBlockIndex(method.name, emptyList())
            }

            val statements = code.statements
            if (statements.isEmpty()) {
                return SpockBlockIndex(method.name, emptyList())
            }

            var currentBlock: SpockBlock? = null
            var currentContinues: SpockBlock? = null
            var blockStartLine = -1

            for ((index, statement) in statements.withIndex()) {
                // Use statementLabels list (preferred over deprecated statementLabel)
                val label = statement.statementLabels?.firstOrNull()
                val spockBlock = label?.let { SpockBlock.fromLabel(it) }

                if (spockBlock != null) {
                    // Close previous block if exists
                    if (currentBlock != null && blockStartLine > 0) {
                        val endLine = getStatementEndLine(statements, index - 1) ?: (statement.lineNumber - 1)
                        blocks.add(BlockSpan(currentBlock, blockStartLine, endLine, currentContinues))
                    }

                    // Start new block
                    currentBlock = spockBlock
                    blockStartLine = statement.lineNumber
                    currentContinues = if (spockBlock == SpockBlock.AND) {
                        // AND continues the previous non-AND block
                        blocks.lastOrNull()?.let { prev ->
                            if (prev.block == SpockBlock.AND) prev.continues else prev.block
                        }
                    } else {
                        null
                    }
                }
            }

            // Close the last block
            if (currentBlock != null && blockStartLine > 0) {
                val lastStatement = statements.lastOrNull()
                val endLine = lastStatement?.lastLineNumber ?: lastStatement?.lineNumber ?: blockStartLine
                blocks.add(BlockSpan(currentBlock, blockStartLine, endLine, currentContinues))
            }

            logger.debug("Built SpockBlockIndex for {} with {} blocks", method.name, blocks.size)
            return SpockBlockIndex(method.name, blocks)
        }

        private fun getStatementEndLine(statements: List<Statement>, index: Int): Int? {
            if (index < 0 || index >= statements.size) return null
            val stmt = statements[index]
            return stmt.lastLineNumber.takeIf { it > 0 } ?: stmt.lineNumber.takeIf { it > 0 }
        }
    }
}
