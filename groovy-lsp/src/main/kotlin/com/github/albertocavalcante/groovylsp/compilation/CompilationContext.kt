package com.github.albertocavalcante.groovylsp.compilation

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import org.codehaus.groovy.ast.ModuleNode
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
     * AST model for navigating and querying the AST.
     */
    val astModel: GroovyAstModel,

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
            astModel: GroovyAstModel,
            workspaceRoot: Path?,
            classpath: List<Path> = emptyList(),
        ): CompilationContext? {
            val moduleNode = result.ast as? ModuleNode ?: return null

            return CompilationContext(
                uri = uri,
                moduleNode = moduleNode,
                astModel = astModel,
                workspaceRoot = workspaceRoot,
                classpath = classpath,
            )
        }
    }
}
