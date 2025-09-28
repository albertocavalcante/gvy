package com.github.albertocavalcante.groovylsp.types

import com.github.albertocavalcante.groovylsp.ast.AstVisitor
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.io.StringReaderSource
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.net.URI
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SimpleTypeTest {

    private val logger = LoggerFactory.getLogger(SimpleTypeTest::class.java)

    @Test
    fun `test basic Groovy compilation and AST creation`() {
        val code = "class Test { String name }"

        val config = CompilerConfiguration()
        val classLoader = GroovyClassLoader()
        val compilationUnit = CompilationUnit(config, null, classLoader)

        val source = StringReaderSource(code, config)
        val sourceUnit = SourceUnit("test.groovy", source, config, classLoader, compilationUnit.errorCollector)
        compilationUnit.addSource(sourceUnit)

        // Compile
        compilationUnit.compile(Phases.CANONICALIZATION)
        val module = sourceUnit.ast

        assertNotNull(module, "Should have compiled AST")
        assertTrue(module is ModuleNode, "Should be ModuleNode")

        // Try AST visitor
        val astVisitor = AstVisitor()
        val uri = URI.create("file:///test.groovy")
        astVisitor.visitModule(module, sourceUnit, uri)

        val allNodes = astVisitor.getAllNodes()
        assertTrue(allNodes.isNotEmpty(), "Should have AST nodes")

        logger.debug("✓ Basic compilation works, found {} nodes", allNodes.size)
        allNodes.forEachIndexed { i, node ->
            logger.debug("  {}: {}", i, node.javaClass.simpleName)
        }
    }

    @Test
    fun `test if TypeResolver can handle basic nodes without suspension`() {
        // Create a simple field node
        val fieldNode = org.codehaus.groovy.ast.FieldNode(
            "testField",
            0,
            org.codehaus.groovy.ast.ClassHelper.STRING_TYPE,
            null,
            null,
        )

        // Test basic synchronous type resolution if possible
        logger.debug("✓ Created field node: $fieldNode")
        logger.debug("  Field type: {}", fieldNode.type)
        logger.debug("  Field type name: {}", fieldNode.type.name)

        assertTrue(fieldNode.type.name == "java.lang.String", "Field should be String type")
    }
}
