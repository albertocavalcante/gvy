package com.github.albertocavalcante.groovylsp.ast

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.stmt.Statement
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Collects references to symbols in Groovy AST.
 * Provides find-references functionality for LSP.
 */
class ReferenceCollector {

    companion object {
        private val logger = LoggerFactory.getLogger(ReferenceCollector::class.java)
    }

    /**
     * Finds all references to a symbol in the given module.
     *
     * @param module The module to search
     * @param symbolName The name of the symbol to find
     * @param symbolType The type of symbol we're looking for
     * @param fileUri The URI of the file being analyzed
     * @return List of locations where the symbol is referenced
     */
    fun findReferences(module: ModuleNode, symbolName: String, symbolType: SymbolType, fileUri: URI): List<Location> {
        val references = mutableListOf<Location>()
        val visitor = ReferenceVisitor(symbolName, symbolType, fileUri, references)

        // Visit all classes in the module
        module.classes.forEach { classNode ->
            visitor.visitClass(classNode)
        }

        logger.debug("Found ${references.size} references to symbol: $symbolName")
        return references
    }

    /**
     * Finds all references to a specific method in the AST.
     */
    fun findMethodReferences(
        module: ModuleNode,
        methodName: String,
        declaringClass: String?,
        fileUri: URI,
    ): List<Location> {
        val references = mutableListOf<Location>()
        val visitor = MethodReferenceVisitor(methodName, declaringClass, fileUri, references)

        module.classes.forEach { classNode ->
            visitor.visitClass(classNode)
        }

        logger.debug("Found ${references.size} references to method: $methodName")
        return references
    }

    /**
     * Finds all implementations or overrides of a method.
     */
    fun findMethodImplementations(
        modules: List<ModuleNode>,
        methodName: String,
        declaringClass: String,
        fileUris: List<URI>,
    ): List<Location> {
        val implementations = mutableListOf<Location>()

        modules.forEachIndexed { index, module ->
            val fileUri = fileUris.getOrNull(index) ?: return@forEachIndexed

            module.classes.forEach { classNode ->
                // Check if this class extends or implements the declaring class
                if (isSubclassOf(classNode, declaringClass)) {
                    // Look for method implementations
                    classNode.methods.forEach { method ->
                        if (method.name == methodName) {
                            implementations.add(
                                Location(
                                    fileUri.toString(),
                                    Range(
                                        Position(method.lineNumber - 1, method.columnNumber - 1),
                                        Position(method.lineNumber - 1, method.columnNumber + method.name.length - 1),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }

        return implementations
    }

    /**
     * Checks if a class is a subclass of another class.
     */
    private fun isSubclassOf(classNode: ClassNode, superClassName: String): Boolean {
        var current = classNode.superClass
        while (current != null && current.name != "java.lang.Object") {
            if (current.nameWithoutPackage == superClassName || current.name == superClassName) {
                return true
            }
            current = current.superClass
        }

        // Check interfaces
        return classNode.interfaces.any {
            it.nameWithoutPackage == superClassName || it.name == superClassName
        }
    }

    /**
     * Builds a call hierarchy for a method.
     */
    fun buildCallHierarchy(
        modules: List<ModuleNode>,
        methodName: String,
        declaringClass: String,
        fileUris: List<URI>,
    ): CallHierarchy {
        val calls = mutableListOf<CallReference>()
        val calledBy = mutableListOf<CallReference>()

        modules.forEachIndexed { index, module ->
            val fileUri = fileUris.getOrNull(index) ?: return@forEachIndexed

            module.classes.forEach { classNode ->
                classNode.methods.forEach { method ->
                    val visitor = CallHierarchyVisitor(methodName, declaringClass, fileUri, method)
                    if (method.code != null) {
                        visitor.visitStatement(method.code)
                        calls.addAll(visitor.foundCalls)
                    }

                    // Check if this method calls our target method
                    if (visitor.foundCalls.isNotEmpty() &&
                        (method.name != methodName || classNode.nameWithoutPackage != declaringClass)
                    ) {
                        calledBy.add(
                            CallReference(
                                methodName = method.name,
                                className = classNode.nameWithoutPackage,
                                location = Location(
                                    fileUri.toString(),
                                    Range(
                                        Position(method.lineNumber - 1, method.columnNumber - 1),
                                        Position(method.lineNumber - 1, method.columnNumber + method.name.length - 1),
                                    ),
                                ),
                            ),
                        )
                    }
                }
            }
        }

        return CallHierarchy(
            targetMethod = "$declaringClass.$methodName",
            calls = calls,
            calledBy = calledBy,
        )
    }
}

/**
 * AST visitor for finding symbol references.
 */
private class ReferenceVisitor(
    private val symbolName: String,
    private val symbolType: SymbolType,
    private val fileUri: URI,
    private val references: MutableList<Location>,
) {

    fun visitClass(classNode: ClassNode) {
        // Visit methods
        classNode.methods.forEach { method ->
            visitMethod(method)
        }

        // Visit fields
        classNode.fields.forEach { field ->
            if (field.name == symbolName && symbolType == SymbolType.FIELD) {
                references.add(createLocation(field.lineNumber, field.columnNumber, symbolName.length))
            }
        }
    }

    private fun visitMethod(method: MethodNode) {
        if (method.name == symbolName && symbolType == SymbolType.METHOD) {
            references.add(createLocation(method.lineNumber, method.columnNumber, method.name.length))
        }

        // Visit method body
        if (method.code != null) {
            visitStatement(method.code)
        }
    }

    private fun visitStatement(statement: Statement?) {
        // Simplified statement visiting - would need full AST visitor in real implementation
        // For now, we'll add basic support for common expression types
    }

    private fun createLocation(line: Int, column: Int, length: Int): Location = Location(
        fileUri.toString(),
        Range(
            Position(line - 1, column - 1),
            Position(line - 1, column + length - 1),
        ),
    )
}

/**
 * AST visitor for finding method call references.
 */
private class MethodReferenceVisitor(
    private val methodName: String,
    private val declaringClass: String?,
    private val fileUri: URI,
    private val references: MutableList<Location>,
) {

    fun visitClass(classNode: ClassNode) {
        classNode.methods.forEach { method ->
            if (method.code != null) {
                visitStatement(method.code)
            }
        }
    }

    private fun visitStatement(statement: Statement?) {
        // Would traverse AST looking for MethodCallExpression nodes
        // This is a simplified version
    }
}

/**
 * AST visitor for building call hierarchy.
 */
private class CallHierarchyVisitor(
    private val targetMethodName: String,
    private val targetClass: String,
    private val fileUri: URI,
    private val currentMethod: MethodNode,
) {
    val foundCalls = mutableListOf<CallReference>()

    fun visitStatement(statement: Statement?) {
        // Would traverse AST looking for method calls
        // This is a simplified version
    }
}

/**
 * Represents a call reference in the call hierarchy.
 */
data class CallReference(val methodName: String, val className: String, val location: Location)

/**
 * Represents the call hierarchy for a method.
 */
data class CallHierarchy(
    val targetMethod: String,
    // Methods called by this method
    val calls: List<CallReference>,
    // Methods that call this method
    val calledBy: List<CallReference>,
)
