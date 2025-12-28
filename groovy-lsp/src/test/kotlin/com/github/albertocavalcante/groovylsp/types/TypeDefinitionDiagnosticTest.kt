package com.github.albertocavalcante.groovylsp.types

import com.github.albertocavalcante.groovylsp.compilation.CompilationContext
import com.github.albertocavalcante.groovylsp.converters.toGroovyPosition
import com.github.albertocavalcante.groovyparser.ast.NodeRelationshipTracker
import com.github.albertocavalcante.groovyparser.ast.visitor.RecursiveAstVisitor
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.io.StringReaderSource
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Diagnostic test to understand what's happening with type resolution.
 */
class TypeDefinitionDiagnosticTest {

    private val logger = LoggerFactory.getLogger(TypeDefinitionDiagnosticTest::class.java)

    @Test
    fun `debug type resolution process`() {
        val code = """
            class Person {
                String name
            }
            def person = new Person()
            person.name = "test"
        """.trimIndent()

        logger.debug("=== DEBUGGING TYPE RESOLUTION ===")
        logger.debug("Input code: {}", code)

        // 1. Compile the code
        val context = compileGroovy(code)
        logger.debug("✓ Compilation completed")
        logger.debug("ModuleNode: {}", context.moduleNode)
        logger.debug("AstModel: {}", context.astModel)

        // 2. Try to find all nodes
        val allNodes = context.astModel.getAllNodes()
        logger.debug("Found {} AST nodes:", allNodes.size)
        allNodes.forEachIndexed { index, node ->
            logger.debug("  {}: {} - {}", index, node.javaClass.simpleName, node)
        }

        // 3. Try position-based lookup
        val testPosition = Position(4, 10) // Should be around "person.name"
        logger.debug("Looking for node at position $testPosition...")
        val nodeAtPosition = context.astModel.getNodeAt(context.uri, testPosition.toGroovyPosition())
        logger.debug("Node at position: $nodeAtPosition")
        logger.debug("Node type: {}", nodeAtPosition?.javaClass?.simpleName)

        // 4. Check if we found a node for type resolution
        if (nodeAtPosition != null) {
            logger.debug("✓ Node found for type resolution")
            logger.debug("Node available for resolution: {}", nodeAtPosition.javaClass.simpleName)
        } else {
            logger.debug("❌ No node found at position - cannot test type resolution")
        }

        logger.debug("=== END DEBUG ===")
    }

    private fun compileGroovy(code: String): CompilationContext {
        val config = CompilerConfiguration()
        val classLoader = GroovyClassLoader()
        val compilationUnit = CompilationUnit(config, null, classLoader)

        val source = StringReaderSource(code, config)
        val sourceUnit = SourceUnit("test.groovy", source, config, classLoader, compilationUnit.errorCollector)
        compilationUnit.addSource(sourceUnit)

        val astModel = RecursiveAstVisitor(NodeRelationshipTracker())
        val uri = URI.create("file:///test.groovy")

        try {
            // Compile to get AST
            compilationUnit.compile(Phases.CANONICALIZATION)

            // Get the module and visit with our AST visitor
            val module = sourceUnit.ast
            astModel.visitModule(module, uri)

            return CompilationContext(
                uri = uri,
                moduleNode = module,
                astModel = astModel,
                workspaceRoot = null,
            )
        } catch (e: Exception) {
            logger.debug("Compilation error: {}", e.message)
            // Even with compilation errors, we might have partial AST
            val module = sourceUnit.ast ?: ModuleNode(sourceUnit)
            astModel.visitModule(module, uri)

            return CompilationContext(
                uri = uri,
                moduleNode = module,
                astModel = astModel,
                workspaceRoot = null,
            )
        }
    }
}
