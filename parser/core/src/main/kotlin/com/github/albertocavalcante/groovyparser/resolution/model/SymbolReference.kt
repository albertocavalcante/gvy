package com.github.albertocavalcante.groovyparser.resolution.model

import com.github.albertocavalcante.groovyparser.resolution.declarations.ResolvedDeclaration

/**
 * Represents a reference to a symbol that may or may not be resolved.
 *
 * @param T The type of declaration this reference points to
 * @param declaration The resolved declaration, or null if not resolved
 */
class SymbolReference<T : ResolvedDeclaration> private constructor(private val declaration: T?, val isSolved: Boolean) {
    /**
     * Returns the resolved declaration.
     * @throws IllegalStateException if the symbol is not resolved
     */
    fun getDeclaration(): T = checkNotNull(declaration) { "Symbol is not solved" }

    /**
     * Returns the resolved declaration or null if not resolved.
     */
    fun getDeclarationOrNull(): T? = declaration

    override fun toString(): String = if (isSolved) {
        "SymbolReference[$declaration]"
    } else {
        "SymbolReference[unsolved]"
    }

    companion object {
        /**
         * Creates a resolved symbol reference.
         */
        fun <T : ResolvedDeclaration> solved(declaration: T): SymbolReference<T> = SymbolReference(declaration, true)

        /**
         * Creates an unsolved symbol reference.
         */
        fun <T : ResolvedDeclaration> unsolved(): SymbolReference<T> = SymbolReference(null, false)
    }
}
