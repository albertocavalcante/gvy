package com.github.albertocavalcante.groovyparser.ast.body

import com.github.albertocavalcante.groovyparser.ast.Node

/**
 * Represents a class declaration: `class Foo { ... }`
 */
class ClassDeclaration(
    name: String,
    val isInterface: Boolean = false,
    val isEnum: Boolean = false,
    val isScript: Boolean = false,
) : TypeDeclaration(name) {

    /** The superclass of this class, if any */
    var superClass: String? = null

    /** Interfaces implemented by this class */
    val implementedTypes: MutableList<String> = mutableListOf()

    /** Methods declared in this class */
    private val methodDecls: MutableList<MethodDeclaration> = mutableListOf()

    /** Fields declared in this class */
    private val fieldDecls: MutableList<FieldDeclaration> = mutableListOf()

    /** Constructors declared in this class */
    private val constructorDecls: MutableList<ConstructorDeclaration> = mutableListOf()

    /** Returns the methods declared in this class */
    val methods: List<MethodDeclaration>
        get() = methodDecls.toList()

    /** Returns the fields declared in this class */
    val fields: List<FieldDeclaration>
        get() = fieldDecls.toList()

    /** Returns the constructors declared in this class */
    val constructors: List<ConstructorDeclaration>
        get() = constructorDecls.toList()

    /**
     * Adds a method to this class.
     */
    fun addMethod(method: MethodDeclaration) {
        methodDecls.add(method)
        setAsParentNodeOf(method)
    }

    /**
     * Adds a field to this class.
     */
    fun addField(field: FieldDeclaration) {
        fieldDecls.add(field)
        setAsParentNodeOf(field)
    }

    /**
     * Adds a constructor to this class.
     */
    fun addConstructor(constructor: ConstructorDeclaration) {
        constructorDecls.add(constructor)
        setAsParentNodeOf(constructor)
    }

    override fun getChildNodes(): List<Node> {
        val children = mutableListOf<Node>()
        children.addAll(fieldDecls)
        children.addAll(constructorDecls)
        children.addAll(methodDecls)
        return children
    }

    override fun toString(): String {
        val prefix = when {
            isInterface -> "interface"
            isEnum -> "enum"
            else -> "class"
        }
        return "$prefix $name"
    }
}
