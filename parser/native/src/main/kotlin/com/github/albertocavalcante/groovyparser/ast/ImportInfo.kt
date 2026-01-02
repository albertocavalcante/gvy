package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.ModuleNode

/**
 * Import information extracted from AST.
 * This is a deterministic, AST-based extraction - no heuristics.
 */
data class ImportInfo(val existingImports: Set<String>, val insertLine: Int) {
    companion object {
        /**
         * Extracts import information from a parsed ModuleNode.
         * This is the DETERMINISTIC approach - uses AST directly.
         *
         * @param ast The parsed ModuleNode
         * @return ImportInfo with existing FQNs and insert position
         */
        fun fromAst(ast: ModuleNode): ImportInfo {
            val imports = extractImportFqns(ast)
            val insertLine = calculateInsertLine(ast)
            return ImportInfo(imports, insertLine)
        }

        /**
         * Extracts fully qualified names from regular (non-static, non-star) imports.
         */
        private fun extractImportFqns(ast: ModuleNode): Set<String> = ast.imports
            .asSequence()
            .mapNotNull { it.className }
            .toSet()

        /**
         * Calculates the line where new imports should be inserted.
         * Returns line after last import, or line after package, or line 0.
         */
        private fun calculateInsertLine(ast: ModuleNode): Int {
            // Find last import line (regular + star + static + static star)
            val lastImportLine = sequenceOf(
                ast.imports.asSequence().map { it.lineNumber },
                ast.starImports.asSequence().map { it.lineNumber },
                ast.staticImports.values.asSequence().map { it.lineNumber },
                ast.staticStarImports.values.asSequence().map { it.lineNumber },
            ).flatten()
                .filter { it > 0 }
                .maxOrNull()

            return when {
                lastImportLine != null -> lastImportLine // Line number is 1-based, we want insert after = next line
                ast.packageName != null -> {
                    val packageLineNumber = ast.getPackage()?.lineNumber ?: 1
                    packageLineNumber // Insert after package declaration
                }
                else -> 0
            }
        }
    }
}
