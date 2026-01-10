package com.github.albertocavalcante.groovylsp.providers.diagnostics

import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.slf4j.LoggerFactory

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

    private val logger = LoggerFactory.getLogger(TypeUsageCollector::class.java)

    private val PRIMITIVES = setOf(
        "int", "long", "short", "byte", "float", "double", "boolean", "char", "void",
        "Integer", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Character", "Void",
    )

    /**
     * Collect all used type names from a ModuleNode.
     * Returns simple names (not FQNs) to match import aliases.
     */
    fun collectUsedTypes(moduleNode: ModuleNode): Set<String> {
        val usedTypes = mutableSetOf<String>()
        val visitor = TypeUsageVisitor(usedTypes)

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

        logger.debug("Collected {} used types from module", usedTypes.size)
        return usedTypes
    }

    private class TypeUsageVisitor(private val usedTypes: MutableSet<String>) {

        val codeVisitor = TypeUsageCodeVisitor()

        fun visitClass(classNode: ClassNode) {
            // Skip synthetic classes
            if (classNode.isSynthetic) return

            // Collect superclass (skip Object)
            classNode.superClass?.let { superClass ->
                if (superClass.nameWithoutPackage != "Object") {
                    collectTypeReference(superClass)
                }
            }

            // Collect interfaces
            classNode.interfaces?.forEach { collectTypeReference(it) }

            // Collect annotations
            classNode.annotations?.forEach { collectAnnotation(it) }

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

            // Collect annotations
            method.annotations?.forEach { collectAnnotation(it) }

            // Collect return type (skip void/Object)
            val returnType = method.returnType
            if (returnType.nameWithoutPackage != "void" && returnType.nameWithoutPackage != "Object") {
                collectTypeReference(returnType)
            }

            // Collect parameter types
            method.parameters?.forEach { param ->
                collectTypeReference(param.type)
                param.annotations?.forEach { collectAnnotation(it) }
            }

            // Visit method body
            method.code?.visit(codeVisitor)
        }

        private fun visitField(field: FieldNode) {
            // Skip synthetic fields
            if (field.isSynthetic) return

            field.annotations?.forEach { collectAnnotation(it) }
            collectTypeReference(field.type)
            field.initialExpression?.visit(codeVisitor)
        }

        private fun visitProperty(property: PropertyNode) {
            property.annotations?.forEach { collectAnnotation(it) }
            collectTypeReference(property.type)
        }

        private fun collectTypeReference(classNode: ClassNode?) {
            if (classNode == null) return

            // Get simple name
            val simpleName = classNode.nameWithoutPackage
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

        private fun collectAnnotation(annotation: AnnotationNode) {
            collectTypeReference(annotation.classNode)
        }

        inner class TypeUsageCodeVisitor : CodeVisitorSupport() {

            override fun visitConstructorCallExpression(call: ConstructorCallExpression) {
                collectTypeReference(call.type)
                super.visitConstructorCallExpression(call)
            }

            override fun visitClassExpression(expression: ClassExpression) {
                collectTypeReference(expression.type)
                super.visitClassExpression(expression)
            }

            override fun visitDeclarationExpression(expression: DeclarationExpression) {
                // Only collect if type is explicit (not inferred as Object)
                val type = expression.variableExpression.type
                if (type.nameWithoutPackage != "Object" || expression.variableExpression.isDynamicTyped.not()) {
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
                // Handle Class.property access like TypeName.class
                val obj = expression.objectExpression
                if (obj is ClassExpression) {
                    collectTypeReference(obj.type)
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
        }
    }
}
