package com.github.albertocavalcante.groovylsp.indexing

import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.slf4j.LoggerFactory

class UnifiedIndexer(private val writers: List<IndexWriter>) {

    private val logger = LoggerFactory.getLogger(UnifiedIndexer::class.java)

    fun indexDocument(path: String, content: String) {
        writers.forEach { it.visitDocumentStart(path, content) }

        try {
            val config = CompilerConfiguration()
            // Run in migration mode / lenient if possible, or just standard
            val classLoader = GroovyClassLoader()
            val unit = CompilationUnit(config, null, classLoader)
            unit.addSource(path, content)
            unit.compile(Phases.CONVERSION)

            val module = unit.ast.modules.firstOrNull()
            if (module != null) {
                visitModule(module)
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse {}: {}", path, e.message)
        }

        writers.forEach { it.visitDocumentEnd() }
    }

    private fun visitModule(module: ModuleNode) {
        for (classNode in module.classes) {
            visitClass(classNode)
        }
    }

    private fun visitClass(classNode: ClassNode) {
        val range =
            Range(classNode.lineNumber, classNode.columnNumber, classNode.lastLineNumber, classNode.lastColumnNumber)
        val symbol = SymbolGenerator.forClass(classNode)

        writers.forEach {
            // Definition of the class
            it.visitDefinition(range, symbol, false)
        }

        for (method in classNode.methods) {
            visitMethod(classNode, method)
        }
    }

    private fun visitMethod(owner: ClassNode, method: MethodNode) {
        val range = Range(method.lineNumber, method.columnNumber, method.lastLineNumber, method.lastColumnNumber)
        val symbol = SymbolGenerator.forMethod(owner, method)

        writers.forEach {
            it.visitDefinition(range, symbol, false)
        }

        // Body visiting would go here with a CodeVisitor
        // method.code?.visit(object : CodeVisitorSupport() { ... })
    }
}
