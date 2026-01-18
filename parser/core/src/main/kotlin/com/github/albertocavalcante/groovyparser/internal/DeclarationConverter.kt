package com.github.albertocavalcante.groovyparser.internal

import com.github.albertocavalcante.groovyparser.ast.AnnotationExpr
import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ConstructorDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.groovyparser.ast.expr.Expression
import com.github.albertocavalcante.groovyparser.ast.stmt.Statement
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import java.lang.reflect.Modifier

/**
 * Converts class, method, field, and constructor declarations.
 *
 * Handles ~150 lines of declaration conversion logic.
 */
internal class DeclarationConverter(
    private val setRange: (Node, ASTNode) -> Unit,
    @Suppress("UnusedPrivateProperty") // Reserved for future internal comment attachment logic
    private val commentAttacher: (Node, ASTNode) -> Unit,
) {

    /**
     * Converts a Groovy ClassNode to a ClassDeclaration.
     */
    fun convertClass(
        classNode: ClassNode,
        convertAnnotations: (List<AnnotationNode>?, Node) -> Unit,
    ): ClassDeclaration {
        val classDecl = ClassDeclaration(
            name = classNode.nameWithoutPackage,
            isInterface = classNode.isInterface,
            isEnum = classNode.isEnum,
            isScript = classNode.isScript,
        )

        // Convert annotations
        convertAnnotations(classNode.annotations, classDecl)

        // Set superclass
        classNode.superClass?.let { superClass ->
            if (superClass.name != "java.lang.Object" && superClass.name != "groovy.lang.Script") {
                classDecl.superClass = superClass.name
            }
        }

        // Set implemented interfaces
        classNode.interfaces?.forEach { iface ->
            classDecl.implementedTypes.add(iface.name)
        }

        setRange(classDecl, classNode)
        return classDecl
    }

    /**
     * Converts a Groovy FieldNode to a FieldDeclaration.
     */
    fun convertField(
        fieldNode: FieldNode,
        convertAnnotations: (List<AnnotationNode>?, Node) -> Unit,
    ): FieldDeclaration {
        val field = FieldDeclaration(
            name = fieldNode.name,
            type = fieldNode.type?.name ?: "Object",
        )
        field.isStatic = Modifier.isStatic(fieldNode.modifiers)
        field.isFinal = Modifier.isFinal(fieldNode.modifiers)
        field.hasInitializer = fieldNode.hasInitialExpression()
        convertAnnotations(fieldNode.annotations, field)
        setRange(field, fieldNode)
        return field
    }

    /**
     * Converts a Groovy PropertyNode to a FieldDeclaration.
     */
    fun convertProperty(
        propertyNode: org.codehaus.groovy.ast.PropertyNode,
        convertAnnotations: (List<AnnotationNode>?, Node) -> Unit,
    ): FieldDeclaration {
        val field = FieldDeclaration(
            name = propertyNode.name,
            type = propertyNode.type?.name ?: "Object",
        )
        field.isStatic = Modifier.isStatic(propertyNode.modifiers)
        field.isFinal = Modifier.isFinal(propertyNode.modifiers)
        field.hasInitializer = propertyNode.field?.hasInitialExpression() ?: false
        convertAnnotations(propertyNode.annotations, field)
        setRange(field, propertyNode)
        return field
    }

    /**
     * Converts a Groovy MethodNode to a MethodDeclaration.
     */
    fun convertMethod(
        methodNode: MethodNode,
        convertAnnotations: (List<AnnotationNode>?, Node) -> Unit,
        convertStmt: (org.codehaus.groovy.ast.stmt.Statement) -> Statement?,
    ): MethodDeclaration {
        val returnType = methodNode.returnType?.name ?: "Object"
        val method = MethodDeclaration(
            name = methodNode.name,
            returnType = returnType,
        )
        method.isStatic = Modifier.isStatic(methodNode.modifiers)
        method.isAbstract = Modifier.isAbstract(methodNode.modifiers)
        method.isFinal = Modifier.isFinal(methodNode.modifiers)

        // Convert annotations
        convertAnnotations(methodNode.annotations, method)

        // Convert parameters
        methodNode.parameters?.forEach { param ->
            val parameter = Parameter(
                name = param.name,
                type = param.type?.name ?: "Object",
            )
            convertAnnotations(param.annotations, parameter)
            setRange(parameter, param)
            method.addParameter(parameter)
        }

        // Convert method body
        methodNode.code?.let { code ->
            method.body = convertStmt(code)
        }

        setRange(method, methodNode)
        return method
    }

    /**
     * Converts a Groovy MethodNode (representing a constructor) to a ConstructorDeclaration.
     */
    fun convertConstructor(
        constructorNode: MethodNode,
        className: String,
        convertAnnotations: (List<AnnotationNode>?, Node) -> Unit,
    ): ConstructorDeclaration {
        val constructor = ConstructorDeclaration(name = className)

        // Convert annotations
        convertAnnotations(constructorNode.annotations, constructor)

        // Convert parameters
        constructorNode.parameters?.forEach { param ->
            val parameter = Parameter(
                name = param.name,
                type = param.type?.name ?: "Object",
            )
            convertAnnotations(param.annotations, parameter)
            setRange(parameter, param)
            constructor.addParameter(parameter)
        }

        setRange(constructor, constructorNode)
        return constructor
    }

    /**
     * Converts Groovy annotation nodes to our AST annotations.
     */
    fun convertAnnotations(
        annotations: List<AnnotationNode>?,
        target: Node,
        convertExpr: (org.codehaus.groovy.ast.expr.Expression) -> Expression,
    ) {
        annotations?.forEach { ann ->
            val annotation = AnnotationExpr(ann.classNode?.name ?: "Unknown")

            // Convert annotation members
            ann.members?.forEach { (name, value) ->
                annotation.addMember(name, convertExpr(value))
            }

            setRange(annotation, ann)
            target.addAnnotation(annotation)
        }
    }
}
