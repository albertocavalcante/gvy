package com.github.albertocavalcante.groovylsp.utils

import com.github.albertocavalcante.groovyparser.ast.ImportInfo
import org.codehaus.groovy.ast.ModuleNode
import org.eclipse.lsp4j.Position

/**
 * Utilities for working with import statements in Groovy files.
 *
 * **Design:**
 * - Primary: Delegates to [ImportInfo.fromAst] for deterministic AST-based extraction.
 * - Fallback: Minimal heuristics when AST is unavailable (properly annotated per AGENTS.md).
 */
object ImportUtils {

    private const val IMPORT_PREFIX = "import "
    private const val IMPORT_STATIC_PREFIX = "import static "

    /**
     * Extracts import information from AST (deterministic) or content (heuristic fallback).
     *
     * @param ast The ModuleNode if available (preferred)
     * @param content The file content (used only as fallback)
     * @return Pair of (existing import FQNs, insert position)
     */
    fun extractImportInfo(ast: ModuleNode?, content: String): Pair<Set<String>, Position> = if (ast != null) {
        // DETERMINISTIC: Use AST-based extraction
        val info = ImportInfo.fromAst(ast)
        info.existingImports to Position(info.insertLine, 0)
    } else {
        // HEURISTIC FALLBACK: See inline comments
        extractExistingImports(content) to findImportInsertPosition(content)
    }

    /**
     * Extracts existing import FQNs from AST (deterministic).
     * This is the preferred method when AST is available.
     *
     * @param ast The ModuleNode from parsing
     * @return Set of fully qualified names that are already imported
     */
    fun extractExistingImportsFromAst(ast: ModuleNode): Set<String> = ImportInfo.fromAst(ast).existingImports

    /**
     * Finds the import insertion position from AST (deterministic).
     * Returns position after the last import, or after package declaration.
     *
     * @param ast The ModuleNode from parsing
     * @return Position for inserting a new import
     */
    fun findImportInsertPositionFromAst(ast: ModuleNode): Position = Position(ImportInfo.fromAst(ast).insertLine, 0)

    // ============================================================================
    // HEURISTIC FALLBACKS - Used only when AST is unavailable (e.g., broken syntax)
    // ============================================================================

    /**
     * Extracts existing import FQNs from file content.
     *
     * NOTE: HEURISTIC - This is a fallback when AST is unavailable.
     * Uses simple line-by-line scanning, doesn't handle all edge cases.
     * It is retained to support auto-import when the file is in a non-parsable state.
     *
     * @param content The file content
     * @return Set of fully qualified names that are already imported
     */
    internal fun extractExistingImports(content: String): Set<String> {
        // NOTE: Simple line-by-line scan - intentionally minimal since it's a fallback
        val imports = mutableSetOf<String>()

        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith(IMPORT_PREFIX) && !trimmed.startsWith(IMPORT_STATIC_PREFIX) -> {
                    // Extract FQN: "import foo.Bar;" -> "foo.Bar"
                    val fqn = trimmed
                        .removePrefix(IMPORT_PREFIX)
                        .removeSuffix(";")
                        .trim()
                    // Skip star imports
                    if (!fqn.endsWith(".*")) {
                        imports.add(fqn)
                    }
                }
                // Stop at first code line (class, interface, etc.)
                isCodeDeclaration(trimmed) -> break
            }
        }

        return imports
    }

    /**
     * Finds the position where a new import should be inserted.
     *
     * NOTE: HEURISTIC - This is a fallback when AST is unavailable.
     * Uses simple line-by-line scanning.
     * It is retained to support auto-import when the file is in a non-parsable state.
     *
     * @param content The file content
     * @return Position for inserting a new import
     */
    internal fun findImportInsertPosition(content: String): Position {
        val lines = content.lines()
        var lastImportLine = -1
        var packageLine = -1

        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            when {
                trimmed.startsWith("package ") -> packageLine = i
                trimmed.startsWith(IMPORT_PREFIX) -> lastImportLine = i
                isCodeDeclaration(trimmed) -> break
            }
        }

        val insertLine = when {
            lastImportLine >= 0 -> lastImportLine + 1
            packageLine >= 0 -> packageLine + 1
            else -> 0
        }

        return Position(insertLine, 0)
    }

    /**
     * Checks if a line looks like a code declaration (heuristic stop condition).
     */
    private fun isCodeDeclaration(trimmed: String): Boolean = trimmed.startsWith("class ") ||
        trimmed.startsWith("interface ") ||
        trimmed.startsWith("enum ") ||
        trimmed.startsWith("trait ") ||
        trimmed.startsWith("def ") ||
        (trimmed.startsWith("@") && !trimmed.startsWith("@interface")) ||
        trimmed.startsWith("public class ") ||
        trimmed.startsWith("abstract class ")
}
