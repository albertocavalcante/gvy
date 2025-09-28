package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovylsp.ast.AstVisitor
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilationUnit
import java.net.URI
import java.nio.file.Path

/**
 * Context information for compilation and type resolution operations.
 * Provides access to all necessary components for analyzing Groovy code.
 */
data class CompilationContext(
    /**
     * The URI of the file being analyzed.
     */
    val uri: URI,

    /**
     * The main module node of the compiled file.
     */
    val moduleNode: ModuleNode,

    /**
     * The Groovy compilation unit containing all compilation information.
     */
    val compilationUnit: CompilationUnit,

    /**
     * AST visitor for navigating and querying the AST.
     */
    val astVisitor: AstVisitor,

    /**
     * Workspace root path for resolving relative imports and dependencies.
     */
    val workspaceRoot: Path?,

    /**
     * List of classpath entries for dependency resolution.
     */
    val classpath: List<Path> = emptyList(),
) {
    companion object {
        /**
         * Creates a CompilationContext from a CompilationResult and additional info.
         */
        fun from(
            uri: URI,
            result: CompilationResult,
            astVisitor: AstVisitor,
            workspaceRoot: Path?,
            classpath: List<Path> = emptyList(),
        ): CompilationContext? {
            val moduleNode = result.ast as? ModuleNode ?: return null

            // Create a minimal compilation unit if we don't have one
            val compilationUnit = CompilationUnit()
            // Use the actual source text if available, fallback to empty string if not
            val sourceText = result.sourceText ?: ""
            compilationUnit.addSource(uri.toString(), sourceText)

            return CompilationContext(
                uri = uri,
                moduleNode = moduleNode,
                compilationUnit = compilationUnit,
                astVisitor = astVisitor,
                workspaceRoot = workspaceRoot,
                classpath = classpath,
            )
        }
    }
}
