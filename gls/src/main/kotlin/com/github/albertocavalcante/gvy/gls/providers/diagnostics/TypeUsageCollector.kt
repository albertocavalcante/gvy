// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.diagnostics

import com.github.albertocavalcante.nativeapi.SourceAnnotationIndex
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.syntax.Types
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Collects all type names used in a ModuleNode.
 *
 * This is the deterministic AST-based approach - no heuristics.
 * Used to support unused import detection by identifying all type references.
 *
 * Collected usage contexts:
 * - Variable declarations (type)
 * - Method parameters (type)
 * - Method return types
 * - Constructor calls (new TypeName())
 * - Class/Interface declarations (extends, implements)
 * - Static method calls (TypeName.method())
 * - Class expressions (TypeName.class)
 * - Annotations (@TypeName)
 * - Generic type arguments (List<TypeName>)
 * - Catch clauses (catch(TypeName e))
 * - Cast expressions ((TypeName) expr)
 * - Closure parameters
 * - Field and property declarations
 */
object TypeUsageCollector {

    private val logger = KotlinLogging.logger {}

    /**
     * Primitive types and their boxed equivalents that don't require imports.
     *
     * Boxed types (Integer, Long, etc.) are intentionally included here even though they're
     * technically classes from java.lang. This is a deliberate design choice: explicit imports
     * of boxed types are unnecessary in Groovy due to autoboxing, and marking them as "primitives"
     * ensures their imports are flagged as unused (which is the expected behavior).
     */
    private val PRIMITIVES = setOf(
        "int", "long", "short", "byte", "float", "double", "boolean", "char", "void",
        "Integer", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Character", "Void",
    )

    /**
     * Collect all used type names from a ModuleNode.
     * Returns simple names (not FQNs) to match import aliases.
     */
    fun collectUsedTypes(moduleNode: ModuleNode, includeAnnotations: Boolean = true): Set<String> {
        val usedTypes = mutableSetOf<String>()
        val importAliases = moduleNode.imports.mapNotNull { importNode ->
            val alias = importNode.alias
            val typeName = importNode.type?.name
            if (alias == null || typeName == null) null else alias to typeName
        }.toMap()
        val visitor = TypeUsageVisitor(usedTypes, importAliases)

        // Visit all classes
        moduleNode.classes.forEach { classNode ->
            visitor.visitClass(classNode)
        }

        // Visit script statements (for scripts without explicit class)
        moduleNode.statementBlock?.visit(visitor.codeVisitor)

        // Visit top-level methods in scripts
        moduleNode.methods.forEach { method ->
            visitor.visitMethod(method)
        }

        if (includeAnnotations) {
            SourceAnnotationIndex.fromModule(moduleNode).references.forEach { reference ->
                usedTypes.add((reference.binaryName ?: reference.spelling).substringAfterLast('.'))
            }
        }

        logger.debug { "Collected ${usedTypes.size} used types from module" }
        return usedTypes
    }

    private class TypeUsageVisitor(
        private val usedTypes: MutableSet<String>,
        private val importAliases: Map<String, String>,
    ) {
        private val visitedTypes = Collections.newSetFromMap(IdentityHashMap<ClassNode, Boolean>())

        val codeVisitor = TypeUsageCodeVisitor()

        fun visitClass(classNode: ClassNode) {
            // Skip synthetic classes
            if (classNode.isSynthetic) return

            // Collect superclass (skip Object)
            classNode.unresolvedSuperClass?.let { superClass ->
                if (superClass.nameWithoutPackage != "Object") {
                    collectTypeReference(superClass)
                }
            }

            // Collect interfaces
            classNode.unresolvedInterfaces?.forEach { collectTypeReference(it) }

            // Visit fields
            classNode.fields.forEach { visitField(it) }

            // Visit properties
            classNode.properties.forEach { visitProperty(it) }

            // Visit methods
            classNode.methods.forEach { visitMethod(it) }

            // Visit constructors
            classNode.declaredConstructors.forEach { visitMethod(it) }

            // Visit inner classes
            classNode.innerClasses?.forEach { visitClass(it) }
        }

        fun visitMethod(method: MethodNode) {
            // Skip synthetic methods
            if (method.isSynthetic) return

            // Collect return type (skip void/Object)
            val returnType = method.returnType
            if (returnType.nameWithoutPackage != "void" && returnType.nameWithoutPackage != "Object") {
                collectTypeReference(returnType)
            }

            // Collect parameter types
            method.parameters?.forEach { param ->
                collectTypeReference(param.type)
            }

            // Visit method body
            method.code?.visit(codeVisitor)
        }

        private fun visitField(field: FieldNode) {
            // Skip synthetic fields
            if (field.isSynthetic) return

            collectTypeReference(field.type)
            field.initialExpression?.visit(codeVisitor)
        }

        private fun visitProperty(property: PropertyNode) {
            collectTypeReference(property.type)
        }

        private fun collectTypeReference(classNode: ClassNode?) {
            if (classNode == null || !visitedTypes.add(classNode)) return

            val simpleName = importUsageName(classNode)
            if (simpleName.isNotEmpty() && simpleName !in PRIMITIVES && simpleName != "Object") {
                usedTypes.add(simpleName)
            }

            // Collect generic type arguments
            classNode.genericsTypes?.forEach { genericType ->
                genericType.type?.let { collectTypeReference(it) }
                genericType.upperBounds?.forEach { collectTypeReference(it) }
                genericType.lowerBound?.let { collectTypeReference(it) }
            }
        }

        private fun importUsageName(classNode: ClassNode): String {
            // Canonicalization may turn the separator in written nested types into '$'.
            // Match the resolved import and its suffix so '$' within an alias remains unambiguous.
            val spelling = classNode.unresolvedName
            val qualifier = spelling.substringBefore('.')
            val isQualifiedType = '.' in spelling &&
                (qualifier in importAliases || qualifier.firstOrNull()?.isUpperCase() == true)
            val resolvedAlias = matchingResolvedImport(classNode, spelling)
            return when {
                isQualifiedType -> spelling.substringBefore('.')
                resolvedAlias != null -> resolvedAlias
                '$' in classNode.name -> spelling.takeUnless { '.' in it || '$' in it }.orEmpty()
                else -> classNode.nameWithoutPackage
            }
        }

        private fun matchingResolvedImport(classNode: ClassNode, spelling: String): String? {
            val resolvedName = classNode.name
            return importAliases.entries.firstOrNull { (alias, importedName) ->
                val isImportedType = resolvedName == importedName || resolvedName.startsWith("${importedName}\$")
                isImportedType && spelling == alias + resolvedName.removePrefix(importedName)
            }?.key
        }

        inner class TypeUsageCodeVisitor : CodeVisitorSupport() {

            override fun visitConstructorCallExpression(call: ConstructorCallExpression) {
                collectTypeReference(call.type)
                // Explicitly traverse constructor arguments to collect types within them
                // (CodeVisitorSupport.visitConstructorCallExpression only does call.arguments.visit,
                // so we do it directly here and skip the super call)
                call.arguments?.visit(this)
            }

            override fun visitClassExpression(expression: ClassExpression) {
                collectTypeReference(expression.type)
                super.visitClassExpression(expression)
            }

            override fun visitDeclarationExpression(expression: DeclarationExpression) {
                // Only collect if type is explicit (not inferred as Object)
                // Collect type reference when BOTH conditions are met:
                // 1. Type is not Object (explicit type annotation)
                // 2. NOT dynamically typed (has static type information)
                val type = expression.variableExpression.type
                if (type.nameWithoutPackage != "Object" && expression.variableExpression.isDynamicTyped.not()) {
                    collectTypeReference(type)
                }
                super.visitDeclarationExpression(expression)
            }

            override fun visitCastExpression(expression: CastExpression) {
                collectTypeReference(expression.type)
                super.visitCastExpression(expression)
            }

            override fun visitStaticMethodCallExpression(call: StaticMethodCallExpression) {
                collectTypeReference(call.ownerType)
                super.visitStaticMethodCallExpression(call)
            }

            override fun visitPropertyExpression(expression: PropertyExpression) {
                // Handle qualified access to a type or nested type.
                val obj = expression.objectExpression

                // Case 1: ClassExpression.class (e.g., Date.class in simple assignments)
                if (obj is ClassExpression) {
                    collectTypeReference(obj.type)
                } else if (obj is VariableExpression) {
                    // Case 2: a qualified access where the variable name is a type
                    // At conversion, the receiver may still be a VariableExpression.
                    val typeName = obj.name
                    val property = expression.propertyAsString
                    val isTypeAccess = property == "class" || property?.firstOrNull()?.isUpperCase() == true
                    val isLocalVariable = obj.accessedVariable is VariableExpression
                    val isTypeName = typeName in importAliases || typeName.firstOrNull()?.isUpperCase() == true
                    if (isTypeName && isTypeAccess && !isLocalVariable) {
                        usedTypes.add(typeName)
                    }
                }

                super.visitPropertyExpression(expression)
            }

            override fun visitCatchStatement(statement: CatchStatement) {
                collectTypeReference(statement.variable.type)
                super.visitCatchStatement(statement)
            }

            override fun visitClosureExpression(expression: ClosureExpression) {
                // Collect parameter types in closures
                expression.parameters?.forEach { param ->
                    if (param.type.nameWithoutPackage != "Object") {
                        collectTypeReference(param.type)
                    }
                }
                super.visitClosureExpression(expression)
            }

            override fun visitBinaryExpression(expression: BinaryExpression) {
                // Collect type from instanceof expressions (e.g., "x instanceof List")
                // The instanceof operator is represented as a BinaryExpression with operation type KEYWORD_INSTANCEOF
                if (expression.operation.type == Types.KEYWORD_INSTANCEOF) {
                    collectTypeReference(expression.rightExpression.type)
                }
                super.visitBinaryExpression(expression)
            }
        }
    }
}
