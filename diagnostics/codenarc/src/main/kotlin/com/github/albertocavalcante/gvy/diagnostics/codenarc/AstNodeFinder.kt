package com.github.albertocavalcante.gvy.diagnostics.codenarc

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression

/**
 * Finds AST nodes by line number for precise diagnostic positioning.
 *
 * This class enables AST-aware diagnostic positioning by locating specific
 * AST nodes based on CodeNarc violation line numbers. Instead of using
 * heuristic string matching, we can extract exact positions from the AST.
 *
 * Uses the native Groovy AST (ModuleNode) which is already parsed and
 * cached by the LSP compilation service.
 *
 * @param moduleNode The native Groovy AST from compilation
 * @see AstAwareRangeCalculator for extracting ranges from found nodes
 */
class AstNodeFinder(private val moduleNode: ModuleNode) {

    /**
     * Find a ClassNode at the given line (for ClassName rule).
     *
     * Searches all classes in the module, including nested/inner classes.
     *
     * @param groovyLine 1-based line number from CodeNarc violation
     * @return The ClassNode declared at that line, or null if not found
     */
    fun findClassAtLine(groovyLine: Int): ClassNode? = findClassAtLineRecursive(moduleNode.classes, groovyLine)

    private fun findClassAtLineRecursive(classes: List<ClassNode>, groovyLine: Int): ClassNode? {
        for (classNode in classes) {
            // Check if this class is declared at the target line
            if (classNode.lineNumber == groovyLine) {
                return classNode
            }
            // Check inner classes recursively
            val innerClasses = classNode.innerClasses?.asSequence()?.toList() ?: emptyList()
            val found = findClassAtLineRecursive(innerClasses, groovyLine)
            if (found != null) {
                return found
            }
        }
        return null
    }

    /**
     * Find a MethodNode at the given line (for MethodName rule).
     *
     * Searches all classes in the module, including nested/inner classes.
     *
     * @param groovyLine 1-based line number from CodeNarc violation
     * @return The MethodNode declared at that line, or null if not found
     */
    fun findMethodAtLine(groovyLine: Int): MethodNode? = findMethodAtLineRecursive(moduleNode.classes, groovyLine)

    private fun findMethodAtLineRecursive(classes: List<ClassNode>, groovyLine: Int): MethodNode? {
        for (classNode in classes) {
            // Search methods in this class
            val method = classNode.methods.find { it.lineNumber == groovyLine }
            if (method != null) {
                return method
            }
            // Search in inner classes
            val innerClasses = classNode.innerClasses?.asSequence()?.toList() ?: emptyList()
            val found = findMethodAtLineRecursive(innerClasses, groovyLine)
            if (found != null) {
                return found
            }
        }
        return null
    }

    /**
     * Find a FieldNode at the given line (for FieldName rule).
     *
     * Searches all classes in the module, including nested/inner classes.
     *
     * @param groovyLine 1-based line number from CodeNarc violation
     * @return The FieldNode declared at that line, or null if not found
     */
    fun findFieldAtLine(groovyLine: Int): FieldNode? = findFieldAtLineRecursive(moduleNode.classes, groovyLine)

    private fun findFieldAtLineRecursive(classes: List<ClassNode>, groovyLine: Int): FieldNode? {
        for (classNode in classes) {
            // Search fields in this class
            val field = classNode.fields.find { it.lineNumber == groovyLine }
            if (field != null) {
                return field
            }
            // Search in inner classes
            val innerClasses = classNode.innerClasses?.asSequence()?.toList() ?: emptyList()
            val found = findFieldAtLineRecursive(innerClasses, groovyLine)
            if (found != null) {
                return found
            }
        }
        return null
    }

    /**
     * Find a variable declaration at the given line (for UnusedVariable rule).
     *
     * Uses a visitor to traverse method bodies and find DeclarationExpressions.
     * Searches all classes in the module, including nested/inner classes.
     *
     * @param groovyLine 1-based line number from CodeNarc violation
     * @param variableName The variable name from the violation message
     * @return The Variable node, or null if not found
     */
    fun findVariableAtLine(groovyLine: Int, variableName: String): Variable? {
        val visitor = VariableAtLineFinder(groovyLine, variableName)
        return findVariableAtLineRecursive(moduleNode.classes, visitor)
    }

    private fun findVariableAtLineRecursive(classes: List<ClassNode>, visitor: VariableAtLineFinder): Variable? {
        for (classNode in classes) {
            // Search methods in this class
            for (method in classNode.methods) {
                method.code?.visit(visitor)
                if (visitor.result != null) {
                    return visitor.result
                }
            }
            // Search in inner classes
            val innerClasses = classNode.innerClasses?.asSequence()?.toList() ?: emptyList()
            val found = findVariableAtLineRecursive(innerClasses, visitor)
            if (found != null) {
                return found
            }
        }
        return null
    }

    /**
     * Find an ImportNode at the given line (for UnusedImport rule).
     *
     * Checks all import types: regular, star, static, and static star imports.
     *
     * @param groovyLine 1-based line number from CodeNarc violation
     * @return The ImportNode at that line, or null if not found
     */
    fun findImportAtLine(groovyLine: Int): ImportNode? {
        // Check all import types and return first match
        return moduleNode.imports.find { it.lineNumber == groovyLine }
            ?: moduleNode.starImports.find { it.lineNumber == groovyLine }
            ?: moduleNode.staticImports.values.find { it.lineNumber == groovyLine }
            ?: moduleNode.staticStarImports.values.find { it.lineNumber == groovyLine }
    }
}

/**
 * AST visitor that finds a variable declaration at a specific line with a specific name.
 * Supports both simple declarations (def x = 1) and tuple declarations (def (x, y) = [1, 2]).
 */
private class VariableAtLineFinder(private val targetLine: Int, private val targetName: String) :
    CodeVisitorSupport() {

    var result: Variable? = null
        private set

    override fun visitDeclarationExpression(expression: DeclarationExpression) {
        if (expression.lineNumber == targetLine) {
            when (val left = expression.leftExpression) {
                is VariableExpression -> {
                    if (left.name == targetName) {
                        result = left
                        return
                    }
                }
                is TupleExpression -> {
                    for (expr in left.expressions) {
                        if (expr is VariableExpression && expr.name == targetName) {
                            result = expr
                            return
                        }
                    }
                }
            }
        }
        super.visitDeclarationExpression(expression)
    }
}
