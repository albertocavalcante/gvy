package com.github.albertocavalcante.groovyparser.ast

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.PropertyNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.net.URI
import kotlin.test.assertNotNull

class SymbolTableBuilderTest {

    @Test
    fun `build ignores classpath resolution errors when scanning class members`() {
        val uri = URI.create("file:///test/Exploding.groovy")
        val classNode = ExplodingClassNode("Exploding")
        val astModel = object : GroovyAstModel {
            override fun getParent(node: org.codehaus.groovy.ast.ASTNode) = null
            override fun getChildren(node: org.codehaus.groovy.ast.ASTNode) =
                emptyList<org.codehaus.groovy.ast.ASTNode>()
            override fun getUri(node: org.codehaus.groovy.ast.ASTNode) = uri
            override fun getNodes(uri: URI) = listOf(classNode)
            override fun getAllNodes() = listOf(classNode)
            override fun getAllClassNodes() = listOf(classNode)
            override fun getNodeAt(uri: URI, position: com.github.albertocavalcante.groovyparser.ast.types.Position) =
                null
            override fun getNodeAt(uri: URI, line: Int, character: Int) = null
            override fun contains(
                ancestor: org.codehaus.groovy.ast.ASTNode,
                descendant: org.codehaus.groovy.ast.ASTNode,
            ) = false
        }

        val symbolTable = SymbolTable()

        assertDoesNotThrow {
            symbolTable.buildFromVisitor(astModel)
        }

        assertNotNull(symbolTable.registry.findClassDeclaration(uri, "Exploding"))
    }

    private class ExplodingClassNode(name: String) : ClassNode(name, 0, ClassHelper.OBJECT_TYPE) {
        override fun getFields(): MutableList<FieldNode> = throw NoClassDefFoundError("hudson.model.TaskListener")

        override fun getProperties(): MutableList<PropertyNode> =
            throw NoClassDefFoundError("hudson.model.TaskListener")
    }
}
