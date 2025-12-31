package com.github.albertocavalcante.groovyparser.resolution.groovymodel

import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedClassDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedConstructorDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedFieldDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedMethodDeclaration
import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedTypeParameterDeclaration
import com.github.albertocavalcante.groovyparser.resolution.types.ResolvedReferenceType

/**
 * A resolved class declaration backed by a parsed Groovy AST.
 */
class GroovyParserClassDeclaration(
    private val classDecl: ClassDeclaration,
    private val compilationUnit: CompilationUnit,
    private val typeSolver: TypeSolver,
) : ResolvedClassDeclaration {

    override val name: String = classDecl.name

    override val qualifiedName: String
        get() {
            val pkg = compilationUnit.packageDeclaration
            return if (pkg.isPresent) {
                "${pkg.get().name}.${classDecl.name}"
            } else {
                classDecl.name
            }
        }

    override val superClass: ResolvedReferenceType?
        get() {
            val superClassName = classDecl.superClass ?: return defaultSuperClass()
            return resolveTypeName(superClassName)
        }

    override val interfaces: List<ResolvedReferenceType>
        get() = classDecl.implementedTypes.mapNotNull { resolveTypeName(it) }

    // ClassDeclaration doesn't track abstract/final yet - default to false
    override fun isAbstract(): Boolean = false

    override fun isFinal(): Boolean = false

    override fun getAncestors(): List<ResolvedReferenceType> {
        val ancestors = mutableListOf<ResolvedReferenceType>()
        superClass?.let { ancestors.add(it) }
        ancestors.addAll(interfaces)
        return ancestors
    }

    override fun getDeclaredFields(): List<ResolvedFieldDeclaration> = classDecl.fields.map {
        GroovyParserFieldDeclaration(it, this, typeSolver)
    }

    override fun getDeclaredMethods(): List<ResolvedMethodDeclaration> = classDecl.methods.map {
        GroovyParserMethodDeclaration(it, this, typeSolver)
    }

    override fun getDeclaredConstructors(): List<ResolvedConstructorDeclaration> = classDecl.constructors.map {
        GroovyParserConstructorDeclaration(it, this, typeSolver)
    }

    override fun getTypeParameters(): List<ResolvedTypeParameterDeclaration> {
        // Type parameters from Groovy AST are not yet supported
        return emptyList()
    }

    private fun defaultSuperClass(): ResolvedReferenceType? {
        if (qualifiedName == "java.lang.Object") return null
        val ref = typeSolver.tryToSolveType("java.lang.Object")
        return if (ref.isSolved) ResolvedReferenceType(ref.getDeclaration()) else null
    }

    private fun resolveTypeName(typeName: String): ResolvedReferenceType? {
        // First try the fully qualified name
        val ref = typeSolver.tryToSolveType(typeName)
        if (ref.isSolved) return ResolvedReferenceType(ref.getDeclaration())

        // Try with imports
        for (import in compilationUnit.imports) {
            if (import.name.endsWith(".$typeName")) {
                val importedRef = typeSolver.tryToSolveType(import.name)
                if (importedRef.isSolved) return ResolvedReferenceType(importedRef.getDeclaration())
            }
            if (import.isStarImport) {
                val starRef = typeSolver.tryToSolveType("${import.name}.$typeName")
                if (starRef.isSolved) return ResolvedReferenceType(starRef.getDeclaration())
            }
        }

        // Try same package
        val pkg = compilationUnit.packageDeclaration
        if (pkg.isPresent) {
            val samePackageRef = typeSolver.tryToSolveType("${pkg.get().name}.$typeName")
            if (samePackageRef.isSolved) return ResolvedReferenceType(samePackageRef.getDeclaration())
        }

        // Try java.lang
        val javaLangRef = typeSolver.tryToSolveType("java.lang.$typeName")
        if (javaLangRef.isSolved) return ResolvedReferenceType(javaLangRef.getDeclaration())

        return null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroovyParserClassDeclaration) return false
        return qualifiedName == other.qualifiedName
    }

    override fun hashCode(): Int = qualifiedName.hashCode()

    override fun toString(): String = "GroovyParserClassDeclaration[$qualifiedName]"
}
