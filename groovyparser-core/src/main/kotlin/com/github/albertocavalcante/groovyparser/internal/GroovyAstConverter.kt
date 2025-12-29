package com.github.albertocavalcante.groovyparser.internal

import com.github.albertocavalcante.groovyparser.Position
import com.github.albertocavalcante.groovyparser.Range
import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.ImportDeclaration
import com.github.albertocavalcante.groovyparser.ast.PackageDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ConstructorDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import java.lang.reflect.Modifier

/**
 * Converts Groovy's native AST (ModuleNode) to our custom AST (CompilationUnit).
 */
internal class GroovyAstConverter {

    /**
     * Converts a native Groovy ModuleNode to a CompilationUnit.
     */
    fun convert(moduleNode: ModuleNode): CompilationUnit {
        val unit = CompilationUnit()

        // Convert package declaration
        moduleNode.packageName?.let { packageName ->
            val pkg = PackageDeclaration(packageName.removeSuffix("."))
            unit.setPackageDeclaration(pkg)
        }

        // Convert imports
        moduleNode.imports?.forEach { importNode ->
            unit.addImport(convertImport(importNode))
        }
        moduleNode.starImports?.forEach { importNode ->
            unit.addImport(convertStarImport(importNode))
        }
        moduleNode.staticImports?.values?.forEach { importNode ->
            unit.addImport(convertStaticImport(importNode))
        }
        moduleNode.staticStarImports?.values?.forEach { importNode ->
            unit.addImport(convertStaticStarImport(importNode))
        }

        // Convert classes
        moduleNode.classes?.forEach { classNode ->
            unit.addType(convertClass(classNode))
        }

        return unit
    }

    private fun convertImport(importNode: ImportNode): ImportDeclaration {
        val import = ImportDeclaration(
            name = importNode.type?.name ?: importNode.className ?: "",
            isStatic = false,
            isStarImport = false,
        )
        setRange(import, importNode)
        return import
    }

    private fun convertStarImport(importNode: ImportNode): ImportDeclaration {
        val import = ImportDeclaration(
            name = importNode.packageName?.removeSuffix(".") ?: "",
            isStatic = false,
            isStarImport = true,
        )
        setRange(import, importNode)
        return import
    }

    private fun convertStaticImport(importNode: ImportNode): ImportDeclaration {
        val import = ImportDeclaration(
            name = "${importNode.type?.name}.${importNode.fieldName}",
            isStatic = true,
            isStarImport = false,
        )
        setRange(import, importNode)
        return import
    }

    private fun convertStaticStarImport(importNode: ImportNode): ImportDeclaration {
        val import = ImportDeclaration(
            name = importNode.type?.name ?: "",
            isStatic = true,
            isStarImport = true,
        )
        setRange(import, importNode)
        return import
    }

    private fun convertClass(classNode: ClassNode): ClassDeclaration {
        val classDecl = ClassDeclaration(
            name = classNode.nameWithoutPackage,
            isInterface = classNode.isInterface,
            isEnum = classNode.isEnum,
            isScript = classNode.isScript,
        )

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

        // Convert fields (properties in Groovy are converted to fields)
        classNode.fields?.forEach { fieldNode ->
            if (!fieldNode.isSynthetic) {
                classDecl.addField(convertField(fieldNode))
            }
        }

        // Convert properties (Groovy properties generate synthetic fields, so we convert them directly)
        classNode.properties?.forEach { propertyNode ->
            val field = FieldDeclaration(
                name = propertyNode.name,
                type = propertyNode.type?.name ?: "Object",
            )
            field.isStatic = Modifier.isStatic(propertyNode.modifiers)
            field.isFinal = Modifier.isFinal(propertyNode.modifiers)
            field.hasInitializer = propertyNode.field?.hasInitialExpression() ?: false
            setRange(field, propertyNode)
            classDecl.addField(field)
        }

        // Convert constructors
        classNode.declaredConstructors?.forEach { constructorNode ->
            classDecl.addConstructor(convertConstructor(constructorNode, classNode.nameWithoutPackage))
        }

        // Convert methods
        classNode.methods?.forEach { methodNode ->
            if (!methodNode.isSynthetic) {
                classDecl.addMethod(convertMethod(methodNode))
            }
        }

        setRange(classDecl, classNode)
        return classDecl
    }

    private fun convertField(fieldNode: FieldNode): FieldDeclaration {
        val field = FieldDeclaration(
            name = fieldNode.name,
            type = fieldNode.type?.name ?: "Object",
        )
        field.isStatic = Modifier.isStatic(fieldNode.modifiers)
        field.isFinal = Modifier.isFinal(fieldNode.modifiers)
        field.hasInitializer = fieldNode.hasInitialExpression()
        setRange(field, fieldNode)
        return field
    }

    private fun convertMethod(methodNode: MethodNode): MethodDeclaration {
        val returnType = methodNode.returnType?.name ?: "Object"
        val method = MethodDeclaration(
            name = methodNode.name,
            returnType = returnType,
        )
        method.isStatic = Modifier.isStatic(methodNode.modifiers)
        method.isAbstract = Modifier.isAbstract(methodNode.modifiers)
        method.isFinal = Modifier.isFinal(methodNode.modifiers)

        // Convert parameters
        methodNode.parameters?.forEach { param ->
            val parameter = Parameter(
                name = param.name,
                type = param.type?.name ?: "Object",
            )
            setRange(parameter, param)
            method.addParameter(parameter)
        }

        setRange(method, methodNode)
        return method
    }

    private fun convertConstructor(constructorNode: MethodNode, className: String): ConstructorDeclaration {
        val constructor = ConstructorDeclaration(name = className)

        // Convert parameters
        constructorNode.parameters?.forEach { param ->
            val parameter = Parameter(
                name = param.name,
                type = param.type?.name ?: "Object",
            )
            setRange(parameter, param)
            constructor.addParameter(parameter)
        }

        setRange(constructor, constructorNode)
        return constructor
    }

    private fun setRange(
        node: com.github.albertocavalcante.groovyparser.ast.Node,
        nativeNode: org.codehaus.groovy.ast.ASTNode,
    ) {
        if (nativeNode.lineNumber > 0 && nativeNode.columnNumber > 0) {
            val begin = Position(nativeNode.lineNumber, nativeNode.columnNumber)
            val end = if (nativeNode.lastLineNumber > 0 && nativeNode.lastColumnNumber > 0) {
                Position(nativeNode.lastLineNumber, nativeNode.lastColumnNumber)
            } else {
                begin
            }
            node.range = Range(begin, end)
        }
    }
}
