package com.github.albertocavalcante.gvy.semantics.db

import java.util.Collections

/** Exact declared parameter identities from one captured document, without exposing its graph. */
object ImplementationMethodSignatures {
    /**
     * Existing semantic symbols erase package names. Preserve every qualified alternative instead
     * of guessing which overload a simplified symbol represents. Only source-owned classes qualify.
     */
    fun parameterTypesBySymbol(document: ImplementationDocument, owner: String): Map<String, List<List<String>>> {
        if (document.classes.none { it.symbol == owner }) return emptyMap()
        val methods = document.hierarchy[owner]?.methods.orEmpty()
        return Collections.unmodifiableMap(
            methods.groupBy { it.symbol }.mapValues { (_, declarations) ->
                Collections.unmodifiableList(
                    declarations.map { Collections.unmodifiableList(it.parameterTypes.toList()) }.distinct(),
                )
            },
        )
    }
}
