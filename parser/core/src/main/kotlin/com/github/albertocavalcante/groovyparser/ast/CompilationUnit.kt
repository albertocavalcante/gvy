package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.ast.body.TypeDeclaration
import java.util.Optional

/**
 * The root node of the AST, representing a complete Groovy source file.
 */
class CompilationUnit : Node() {

    /** The package declaration, if present */
    private var packageDecl: PackageDeclaration? = null

    /** Import declarations */
    private val importDecls: MutableList<ImportDeclaration> = mutableListOf()

    /** Type declarations (classes, interfaces, enums, traits) */
    private val typeDecls: MutableList<TypeDeclaration> = mutableListOf()

    /**
     * Returns the package declaration wrapped in an Optional.
     */
    val packageDeclaration: Optional<PackageDeclaration>
        get() = Optional.ofNullable(packageDecl)

    /**
     * Returns the list of import declarations.
     */
    val imports: List<ImportDeclaration>
        get() = importDecls.toList()

    /**
     * Returns the list of type declarations.
     */
    val types: List<TypeDeclaration>
        get() = typeDecls.toList()

    /**
     * Sets the package declaration.
     */
    fun setPackageDeclaration(packageDeclaration: PackageDeclaration) {
        this.packageDecl = packageDeclaration
        setAsParentNodeOf(packageDeclaration)
    }

    /**
     * Adds an import declaration.
     */
    fun addImport(importDeclaration: ImportDeclaration) {
        importDecls.add(importDeclaration)
        setAsParentNodeOf(importDeclaration)
    }

    /**
     * Adds a type declaration.
     */
    fun addType(typeDeclaration: TypeDeclaration) {
        typeDecls.add(typeDeclaration)
        setAsParentNodeOf(typeDeclaration)
    }

    override fun getChildNodes(): List<Node> {
        val children = mutableListOf<Node>()
        packageDecl?.let { children.add(it) }
        children.addAll(importDecls)
        children.addAll(typeDecls)
        return children
    }

    override fun toString(): String {
        val parts = mutableListOf<String>()
        packageDecl?.let { parts.add(it.toString()) }
        if (importDecls.isNotEmpty()) {
            parts.add("${importDecls.size} import(s)")
        }
        if (typeDecls.isNotEmpty()) {
            parts.add("${typeDecls.size} type(s)")
        }
        return "CompilationUnit[${parts.joinToString(", ")}]"
    }
}
