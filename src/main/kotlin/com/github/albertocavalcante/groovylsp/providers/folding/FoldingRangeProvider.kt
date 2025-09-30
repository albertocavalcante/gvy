package com.github.albertocavalcante.groovylsp.providers.folding

import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.ast.stmt.IfStatement
import org.codehaus.groovy.ast.stmt.TryCatchStatement
import org.codehaus.groovy.ast.stmt.WhileStatement
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeKind
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Provides folding ranges for Groovy code structures.
 * Supports folding of classes, methods, closures, blocks, comments, and imports.
 */
class FoldingRangeProvider(private val compilationService: GroovyCompilationService) {

    private val logger = LoggerFactory.getLogger(FoldingRangeProvider::class.java)

    companion object {
        private const val MIN_IMPORTS_FOR_FOLDING = 3
        private const val MIN_LINES_FOR_FOLDING = 1
    }

    /**
     * Provides folding ranges for the specified document.
     *
     * @param uri The document URI
     * @return List of folding ranges for the document
     */
    fun provideFoldingRanges(uri: String): List<FoldingRange> {
        logger.debug("Providing folding ranges for: $uri")

        val documentUri = URI.create(uri)
        val ast = compilationService.getAst(documentUri) as? ModuleNode
            ?: return emptyList()

        val ranges = mutableListOf<FoldingRange>()

        try {
            // Add import folding ranges
            ranges.addAll(createImportFoldingRanges(ast))

            // Add class folding ranges
            ast.classes.forEach { classNode ->
                ranges.addAll(createClassFoldingRanges(classNode))
            }

            // Add comment folding ranges
            ranges.addAll(createCommentFoldingRanges(ast))

            logger.debug("Found ${ranges.size} folding ranges for $uri")
            return ranges.sortedBy { it.startLine }
        } catch (e: Exception) {
            logger.error("Error providing folding ranges for $uri", e)
            return emptyList()
        }
    }

    /**
     * Creates folding ranges for import statements.
     * Groups consecutive imports into a single foldable range.
     */
    private fun createImportFoldingRanges(ast: ModuleNode): List<FoldingRange> {
        val imports = ast.imports + ast.starImports + ast.staticImports.values + ast.staticStarImports.values

        if (imports.size < MIN_IMPORTS_FOR_FOLDING) {
            // Don't fold if less than minimum imports
            return emptyList()
        }

        // Find consecutive import blocks
        val sortedImports = imports.sortedBy { it.lineNumber }
        val foldingRanges = mutableListOf<FoldingRange>()

        var blockStart: Int? = null
        var lastLine = -1

        sortedImports.forEach { import ->
            val currentLine = import.lineNumber - 1 // Convert to 0-based

            if (blockStart == null) {
                blockStart = currentLine
            } else if (currentLine > lastLine + 1) {
                // Gap in imports, close current block and start new one
                if (lastLine - blockStart!! >= MIN_IMPORTS_FOR_FOLDING - 1) { // At least min lines
                    foldingRanges.add(
                        FoldingRange().apply {
                            startLine = blockStart!!
                            endLine = lastLine
                            kind = FoldingRangeKind.Imports
                        },
                    )
                }
                blockStart = currentLine
            }

            lastLine = currentLine
        }

        // Close final block
        if (blockStart != null && lastLine - blockStart >= MIN_IMPORTS_FOR_FOLDING - 1) {
            foldingRanges.add(
                FoldingRange().apply {
                    startLine = blockStart
                    endLine = lastLine
                    kind = FoldingRangeKind.Imports
                },
            )
        }

        return foldingRanges
    }

    /**
     * Creates folding ranges for class-related structures.
     */
    private fun createClassFoldingRanges(classNode: ClassNode): List<FoldingRange> {
        val ranges = mutableListOf<FoldingRange>()

        // Class body folding
        if (canFoldNode(classNode)) {
            ranges.add(
                FoldingRange().apply {
                    startLine = classNode.lineNumber - 1
                    endLine = classNode.lastLineNumber - 1
                    kind = FoldingRangeKind.Region
                    collapsedText = "class ${classNode.nameWithoutPackage} { ... }"
                },
            )
        }

        // Method folding
        classNode.methods.forEach { method ->
            ranges.addAll(createMethodFoldingRanges(method))
        }

        // Inner class folding
        classNode.innerClasses?.forEach { innerClass ->
            ranges.addAll(createClassFoldingRanges(innerClass))
        }

        return ranges
    }

    /**
     * Creates folding ranges for method structures.
     */
    private fun createMethodFoldingRanges(method: MethodNode): List<FoldingRange> {
        val ranges = mutableListOf<FoldingRange>()

        // Method body folding
        if (canFoldNode(method) && method.code != null) {
            val methodSignature = buildMethodSignature(method)
            ranges.add(
                FoldingRange().apply {
                    startLine = method.lineNumber - 1
                    endLine = method.lastLineNumber - 1
                    kind = FoldingRangeKind.Region
                    collapsedText = "$methodSignature { ... }"
                },
            )

            // Fold method body content
            if (method.code is BlockStatement) {
                ranges.addAll(createBlockFoldingRanges(method.code as BlockStatement))
            }
        }

        return ranges
    }

    /**
     * Creates folding ranges for block statements and nested structures.
     */
    private fun createBlockFoldingRanges(block: BlockStatement): List<FoldingRange> {
        val ranges = mutableListOf<FoldingRange>()

        block.statements.forEach { statement ->
            ranges.addAll(createStatementFoldingRanges(statement))
            ranges.addAll(findClosuresInStatement(statement))
        }

        return ranges
    }

    /**
     * Creates folding ranges for individual statements.
     */
    private fun createStatementFoldingRanges(statement: ASTNode): List<FoldingRange> = when (statement) {
        is IfStatement -> createIfStatementRanges(statement)
        is ForStatement -> createForStatementRanges(statement)
        is WhileStatement -> createWhileStatementRanges(statement)
        is TryCatchStatement -> createTryCatchRanges(statement)
        is BlockStatement -> createBlockFoldingRanges(statement)
        else -> emptyList()
    }

    private fun createIfStatementRanges(statement: IfStatement): List<FoldingRange> {
        val ranges = mutableListOf<FoldingRange>()

        if (canFoldNode(statement.ifBlock)) {
            ranges.add(createFoldingRange(statement.ifBlock, "if (...) { ... }"))
        }

        if (statement.elseBlock != null && canFoldNode(statement.elseBlock)) {
            ranges.add(createFoldingRange(statement.elseBlock, "else { ... }"))
        }

        return ranges
    }

    private fun createForStatementRanges(statement: ForStatement): List<FoldingRange> =
        if (canFoldNode(statement.loopBlock)) {
            listOf(createFoldingRange(statement.loopBlock, "for (...) { ... }"))
        } else {
            emptyList()
        }

    private fun createWhileStatementRanges(statement: WhileStatement): List<FoldingRange> =
        if (canFoldNode(statement.loopBlock)) {
            listOf(createFoldingRange(statement.loopBlock, "while (...) { ... }"))
        } else {
            emptyList()
        }

    private fun createTryCatchRanges(statement: TryCatchStatement): List<FoldingRange> {
        val ranges = mutableListOf<FoldingRange>()

        if (canFoldNode(statement.tryStatement)) {
            ranges.add(createFoldingRange(statement.tryStatement, "try { ... }"))
        }

        statement.catchStatements.forEach { catchStatement ->
            if (canFoldNode(catchStatement.code)) {
                val text = "catch (${catchStatement.exceptionType.nameWithoutPackage}) { ... }"
                ranges.add(createFoldingRange(catchStatement.code, text))
            }
        }

        if (statement.finallyStatement != null && canFoldNode(statement.finallyStatement)) {
            ranges.add(createFoldingRange(statement.finallyStatement, "finally { ... }"))
        }

        return ranges
    }

    private fun createFoldingRange(node: ASTNode, collapsedText: String): FoldingRange = FoldingRange().apply {
        startLine = node.lineNumber - 1
        endLine = node.lastLineNumber - 1
        kind = FoldingRangeKind.Region
        this.collapsedText = collapsedText
    }

    /**
     * Finds and creates folding ranges for closure expressions.
     */
    private fun findClosuresInStatement(statement: ASTNode): List<FoldingRange> {
        val ranges = mutableListOf<FoldingRange>()

        // Use a simple recursive approach to find closures
        findClosuresRecursively(statement, ranges)

        return ranges
    }

    /**
     * Recursively searches for closure expressions in AST nodes.
     */
    private fun findClosuresRecursively(node: ASTNode, ranges: MutableList<FoldingRange>) {
        when (node) {
            is ClosureExpression -> {
                if (canFoldNode(node)) {
                    val parameterText = if (node.parameters.isNullOrEmpty()) {
                        ""
                    } else {
                        node.parameters.joinToString(", ") { it.name }
                    }

                    ranges.add(
                        FoldingRange().apply {
                            startLine = node.lineNumber - 1
                            endLine = node.lastLineNumber - 1
                            kind = FoldingRangeKind.Region
                            collapsedText = if (parameterText.isNotEmpty()) {
                                "{ $parameterText -> ... }"
                            } else {
                                "{ ... }"
                            }
                        },
                    )

                    // Recursively fold closure body
                    if (node.code is BlockStatement) {
                        ranges.addAll(createBlockFoldingRanges(node.code as BlockStatement))
                    }
                }
            }
            is BlockStatement -> {
                node.statements.forEach { stmt ->
                    findClosuresRecursively(stmt, ranges)
                }
            }
            is MethodNode -> {
                if (node.code != null) {
                    findClosuresRecursively(node.code, ranges)
                }
            }
            // Add more node types as needed
        }
    }

    /**
     * Creates folding ranges for multi-line comments.
     */
    private fun createCommentFoldingRanges(ast: ModuleNode): List<FoldingRange> {
        val ranges = mutableListOf<FoldingRange>()

        // Note: Groovy AST doesn't preserve comments by default
        // This is a placeholder for when comment parsing is added
        // For now, we could scan the source text directly for multi-line comments

        return ranges
    }

    /**
     * Checks if a node has sufficient size to warrant folding.
     */
    private fun canFoldNode(node: ASTNode?): Boolean {
        if (node == null) return false

        val lineSpan = node.lastLineNumber - node.lineNumber
        return lineSpan >= MIN_LINES_FOR_FOLDING // At least minimum lines for folding
    }

    /**
     * Builds a readable method signature for collapsed text.
     */
    private fun buildMethodSignature(method: MethodNode): String {
        val params = method.parameters.joinToString(", ") { param ->
            "${param.type.nameWithoutPackage} ${param.name}"
        }

        val returnType = if (method.returnType.name != "java.lang.Object") {
            "${method.returnType.nameWithoutPackage} "
        } else {
            ""
        }

        return "$returnType${method.name}($params)"
    }
}
