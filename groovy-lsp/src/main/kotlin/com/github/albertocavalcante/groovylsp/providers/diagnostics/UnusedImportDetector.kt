package com.github.albertocavalcante.groovylsp.providers.diagnostics

import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression

/**
 * Detects unused imports by comparing import statements against actual type usage.
 * Uses TypeUsageCollector for deterministic AST-based type collection.
 *
 * Note: Star imports (java.util.*) and static star imports are NOT checked
 * because determining their usage requires resolving all possible types from the package.
 */
object UnusedImportDetector {

    private val logger = KotlinLogging.logger {}

    /**
     * Detect unused imports in a ModuleNode.
     *
     * @return List of ImportNode that are unused (can be regular or static imports)
     */
    fun detectUnusedImports(moduleNode: ModuleNode): List<ImportNode> {
        val usedTypes = TypeUsageCollector.collectUsedTypes(moduleNode)
        val usedStaticMembers = collectUsedStaticMembers(moduleNode)

        val unusedImports = mutableListOf<ImportNode>()

        // Check regular imports (not star imports)
        // For aliased imports like "import ArrayList as AL", we need to check both:
        // - The alias (AL) which is used in source code
        // - The simple type name (ArrayList) which is what TypeUsageCollector finds
        moduleNode.imports.forEach { importNode ->
            val alias = importNode.alias
            val simpleName = importNode.type?.nameWithoutPackage
            val isUsed = (alias != null && alias in usedTypes) ||
                (simpleName != null && simpleName in usedTypes)
            if (!isUsed) {
                logger.debug { "Detected unused import: ${importNode.className} (alias: $alias)" }
                unusedImports.add(importNode)
            }
        }

        // Check static imports (not star imports)
        moduleNode.staticImports.values.forEach { importNode ->
            val fieldName = importNode.fieldName
            if (fieldName != null && fieldName !in usedStaticMembers) {
                logger.debug { "Detected unused static import: ${importNode.className}.$fieldName" }
                unusedImports.add(importNode)
            }
        }

        // NOTE: Star imports (moduleNode.starImports) and static star imports
        // (moduleNode.staticStarImports) are NOT checked because it's impractical
        // to determine if any type from the package is used.

        // Log includes both regular and static imports in the total count
        val totalImports = moduleNode.imports.size + moduleNode.staticImports.size
        logger.debug { "Found ${unusedImports.size} unused imports out of $totalImports total" }
        return unusedImports
    }

    /**
     * Collect static member names used in the code.
     * These are variable references that match static import field names.
     */
    private fun collectUsedStaticMembers(moduleNode: ModuleNode): Set<String> {
        val staticImportNames = moduleNode.staticImports.values.mapNotNull { it.fieldName }.toSet()
        if (staticImportNames.isEmpty()) return emptySet()

        val usedMembers = mutableSetOf<String>()
        val visitor = StaticMemberUsageVisitor(staticImportNames, usedMembers)

        // Visit class contents - methods and fields
        moduleNode.classes.forEach { classNode ->
            classNode.methods.forEach { method ->
                method.code?.visit(visitor)
            }
            classNode.fields.forEach { field ->
                field.initialExpression?.visit(visitor)
            }
        }

        // Visit script statements
        moduleNode.statementBlock?.visit(visitor)

        // Visit top-level methods
        moduleNode.methods.forEach { method ->
            method.code?.visit(visitor)
        }

        return usedMembers
    }

    private class StaticMemberUsageVisitor(
        private val staticImportNames: Set<String>,
        private val usedMembers: MutableSet<String>,
    ) : CodeVisitorSupport() {

        override fun visitVariableExpression(expression: VariableExpression) {
            val name = expression.name
            UnusedImportDetector.logger.debug {
                "Visiting variable expression: $name (checking against $staticImportNames)"
            }
            if (name in staticImportNames) {
                logger.debug { "Found static import usage: $name" }
                usedMembers.add(name)
            }
            super.visitVariableExpression(expression)
        }

        override fun visitMethodCallExpression(call: MethodCallExpression) {
            // For static method imports like "import static Math.sin",
            // the call might be "sin(x)" without receiver
            val methodName = call.methodAsString
            if (methodName != null && methodName in staticImportNames) {
                usedMembers.add(methodName)
            }
            super.visitMethodCallExpression(call)
        }

        override fun visitStaticMethodCallExpression(call: StaticMethodCallExpression) {
            // Static method calls via import like "emptyList()" become StaticMethodCallExpression
            val methodName = call.method
            UnusedImportDetector.logger.debug {
                "Visiting static method call: $methodName (checking against $staticImportNames)"
            }
            if (methodName in staticImportNames) {
                logger.debug { "Found static import method usage: $methodName" }
                usedMembers.add(methodName)
            }
            super.visitStaticMethodCallExpression(call)
        }
    }
}
