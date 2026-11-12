// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

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

    /** Parameter alternatives of [owner]'s private methods, keyed like [parameterTypesBySymbol]. */
    fun privateParameterTypesBySymbol(
        document: ImplementationDocument,
        owner: String,
    ): Map<String, List<List<String>>> {
        if (document.classes.none { it.symbol == owner }) return emptyMap()
        val methods = document.hierarchy[owner]?.methods.orEmpty().filter { it.isPrivate }
        return Collections.unmodifiableMap(
            methods.groupBy { it.symbol }.mapValues { (_, declarations) ->
                Collections.unmodifiableList(declarations.map { it.parameterTypes.toList() }.distinct())
            },
        )
    }

    /**
     * Direct superclass and interface class symbols (for example `pkg/Parent#`) of a source-owned
     * [owner]; null for classes this document does not own.
     */
    fun supertypes(document: ImplementationDocument, owner: String): SourceSupertypes? {
        if (document.classes.none { it.symbol == owner }) return null
        val type = document.hierarchy[owner] ?: return null
        return SourceSupertypes(type.superclass, Collections.unmodifiableList(type.interfaces.toList()))
    }

    /**
     * Classpath-ancestor methods that a source method of [owner]'s chain overrides only once the
     * ancestors' type variables are substituted from [owner]'s viewpoint, as `name` to erased
     * parameter types. For `class K implements Comparable<K> { int compareTo(K o) }` this is
     * `compareTo` to `[java.lang.Object]`. Method-level type variables never match.
     */
    fun genericOverrides(document: ImplementationDocument, owner: String): Set<Pair<String, List<String>>> =
        genericOverrides(document.classes, document.hierarchy, owner)

    /** [genericOverrides] for request-local facts that were never published. */
    fun genericOverrides(snapshot: ImplementationFacts.Snapshot, owner: String): Set<Pair<String, List<String>>> =
        genericOverrides(snapshot.classes, snapshot.hierarchy, owner)

    private fun genericOverrides(
        classes: List<ImplementationClass>,
        hierarchy: Map<String, ImplementationType>,
        owner: String,
    ): Set<Pair<String, List<String>>> {
        val sources = classes.mapTo(mutableSetOf()) { it.symbol }
        if (owner !in sources) return emptySet()
        val chain = linkedSetOf<String>()
        val pending = ArrayDeque(listOf(owner))
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (chain.add(next)) {
                hierarchy[next]?.let {
                    listOfNotNull(it.superclass) + it.interfaces
                }?.let(pending::addAll)
            }
        }
        val declared = chain.filter { it in sources }.flatMapTo(mutableSetOf()) { source ->
            hierarchy[source]?.methods.orEmpty().filter { !it.isStatic && !it.isPrivate }.map {
                it.name to
                    it.parameterTypes
            }
        }
        val bindings = ImplementationBindings(hierarchy, owner)
        return chain.filterNot { it in sources }.flatMapTo(mutableSetOf()) { ancestor ->
            hierarchy[ancestor]?.methods.orEmpty().filter { !it.isStatic }.mapNotNull { method ->
                val substituted = bindings.parameters(ancestor, method)?.map { it.name + "[]".repeat(it.dimensions) }
                (method.name to method.parameterTypes).takeIf {
                    substituted != null && substituted != method.parameterTypes &&
                        (method.name to substituted) in declared
                }
            }
        }
    }
}

/** Direct parents of a source class, as class symbols; the superclass chain comes before interfaces. */
data class SourceSupertypes(val superclass: String?, val interfaces: List<String>)
