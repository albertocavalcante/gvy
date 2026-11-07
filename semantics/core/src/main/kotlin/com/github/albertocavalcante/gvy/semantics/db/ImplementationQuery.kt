package com.github.albertocavalcante.gvy.semantics.db

import java.net.URI

/** Pure implementation lookup over one detached workspace snapshot. */
object ImplementationQuery {
    private data class ClassDefinition(val uri: URI, val declaration: ImplementationClass)
    private data class MethodDefinition(val owner: String, val method: ImplementationMethod)

    internal class Work {
        var superclassSteps: Long = 0
    }

    internal fun measuredFind(
        documents: Map<URI, ImplementationDocument>,
        origin: URI,
        occurrence: SymbolOccurrence,
    ): Pair<List<ImplementationLocation>, Work> {
        val work = Work()
        return Workspace(documents, work).find(origin, occurrence) to work
    }

    fun find(
        documents: Map<URI, ImplementationDocument>,
        origin: URI,
        occurrence: SymbolOccurrence,
    ): List<ImplementationLocation> = Workspace(documents).find(origin, occurrence)

    private class Workspace(documents: Map<URI, ImplementationDocument>, private val work: Work? = null) {
        private val classes = documents.flatMap { (uri, doc) -> doc.classes.map { ClassDefinition(uri, it) } }
        private val definitions = classes.groupBy { it.declaration.symbol }

        // Conflicting definitions are ambiguous; do not select whichever document happened to be visited first.
        private val hierarchy = documents.values.flatMap { it.hierarchy.entries }
            .groupBy({ it.key }, { it.value }).mapNotNull { (name, facts) ->
                facts.distinct().singleOrNull()?.let { name to it }
            }.toMap()
        private val children = hierarchy.flatMap { (name, type) -> type.parents().map { it to name } }
            .groupBy({ it.first }, { it.second })

        fun find(origin: URI, occurrence: SymbolOccurrence): List<ImplementationLocation> {
            val type = hierarchy[occurrence.symbol]
            return when {
                type != null && (type.isInterface || type.isAbstract) -> classImplementations(origin, occurrence)
                type != null -> emptyList()
                else -> methodTarget(origin, occurrence)?.let(::methodImplementations).orEmpty()
            }
        }

        private fun classImplementations(origin: URI, occurrence: SymbolOccurrence): List<ImplementationLocation> {
            val matchesDeclaration = occurrence.role != OccurrenceRole.DEFINITION ||
                classes.any {
                    it.uri == origin && it.declaration.range == occurrence.range &&
                        it.declaration.symbol == occurrence.symbol
                }
            return if (matchesDeclaration) {
                concreteDescendants(occurrence.symbol).map {
                    ImplementationLocation(it.uri, it.declaration.range)
                }
            } else {
                emptyList()
            }
        }

        private fun methodTarget(origin: URI, occurrence: SymbolOccurrence): MethodDefinition? {
            val targets = hierarchy.flatMap { (owner, type) ->
                type.methods.filter { method ->
                    method.symbol == occurrence.symbol &&
                        (
                            occurrence.role != OccurrenceRole.DEFINITION ||
                                method.range == occurrence.range && definitions[owner]?.any { it.uri == origin } == true
                            )
                }.map { MethodDefinition(owner, it) }
            }
            return targets.singleOrNull()?.takeIf { target ->
                !target.method.isStatic && !target.method.isPrivate &&
                    (target.method.isAbstract || hierarchy[target.owner]?.isInterface == true)
            }
        }

        private fun methodImplementations(target: MethodDefinition): List<ImplementationLocation> {
            val resolved = mutableMapOf<String, MethodDefinition?>()
            return concreteDescendants(target.owner).mapNotNull { candidate ->
                cachedMethod(candidate.declaration.symbol, target.method, resolved)?.let { method ->
                    val uri = definitions[method.owner]?.singleOrNull()?.uri
                    val range = method.method.range
                    if (uri != null && range != null) ImplementationLocation(uri, range) else null
                }
            }.distinct()
        }

        /** A class with no matching declaration or newly implemented interface inherits the parent's exact answer. */
        private fun cachedMethod(
            symbol: String,
            target: ImplementationMethod,
            resolved: MutableMap<String, MethodDefinition?>,
        ): MethodDefinition? {
            val inherited = mutableListOf<String>()
            val visited = mutableSetOf<String>()
            var current = symbol
            while (current !in resolved && visited.add(current)) {
                val type = hierarchy[current]
                val parent = type?.superclass
                if (parent != null && type.interfaces.isEmpty() && matchingMethods(current, target).isEmpty()) {
                    if (work != null) work.superclassSteps++
                    inherited.add(current)
                    current = parent
                } else {
                    resolved[current] = implementationMethod(current, target)
                }
            }
            val result = resolved[current]
            inherited.forEach { resolved[it] = result }
            return result
        }

        private fun concreteDescendants(symbol: String): List<ClassDefinition> {
            val reachable = traverse(children[symbol].orEmpty()) { children[it].orEmpty() } - symbol
            return classes.filter { candidate ->
                val type = hierarchy[candidate.declaration.symbol]
                candidate.declaration.symbol in reachable && definitions[candidate.declaration.symbol]?.size == 1 &&
                    type != null && !type.isInterface && !type.isAbstract
            }
        }

        private fun implementationMethod(symbol: String, target: ImplementationMethod): MethodDefinition? {
            val chain = traverse(listOf(symbol)) {
                if (work != null) work.superclassSteps++
                listOfNotNull(hierarchy[it]?.superclass)
            }
            val classDeclaration = chain.firstNotNullOfOrNull { owner ->
                matchingMethods(owner, target).takeIf { it.isNotEmpty() }
            }
            return if (classDeclaration != null) {
                classDeclaration.singleOrNull()?.let { followBridge(it, target) }
            } else {
                interfaceDefault(chain, target)
            }
        }

        private fun interfaceDefault(chain: Set<String>, target: ImplementationMethod): MethodDefinition? {
            val interfaces = traverse(chain.flatMap { hierarchy[it]?.interfaces.orEmpty() }) {
                hierarchy[it]?.interfaces.orEmpty()
            }
            val declarations = interfaces.associateWith { matchingMethods(it, target) }.filterValues { it.isNotEmpty() }
            // A more-specific abstract declaration also masks a parent's default.
            val shadowed = traverse(declarations.keys.flatMap { hierarchy[it]?.interfaces.orEmpty() }) {
                hierarchy[it]?.interfaces.orEmpty()
            }
            val mostSpecific = declarations.filterKeys { it !in shadowed }.values.flatten()
            return mostSpecific.singleOrNull()?.let { followBridge(it, target) }
        }

        /** Groovy records the selected trait on its generated forwarder, so navigation need not guess trait order. */
        private fun followBridge(declaration: MethodDefinition, target: ImplementationMethod): MethodDefinition? {
            var current = declaration
            val visited = mutableSetOf<String>()
            while (current.method.bridgeOwner != null && visited.add(current.owner)) {
                val owner = checkNotNull(current.method.bridgeOwner)
                current = matchingMethods(owner, target).singleOrNull() ?: return null
            }
            return current.takeIf { it.method.bridgeOwner == null && it.method.isConcrete() }
        }

        private fun matchingMethods(owner: String, target: ImplementationMethod): List<MethodDefinition> =
            hierarchy[owner]?.methods.orEmpty().filter {
                it.name == target.name &&
                    it.parameterTypes == target.parameterTypes
            }
                .map { MethodDefinition(owner, it) }
    }

    private fun ImplementationType.parents(): List<String> = listOfNotNull(superclass) + interfaces

    private fun ImplementationMethod.isConcrete(): Boolean = !isAbstract && !isStatic && !isPrivate

    /** Both deep and cyclic hierarchies use bounded stack space. Insertion order preserves the superclass chain. */
    private fun traverse(start: Collection<String>, next: (String) -> Collection<String>): Set<String> {
        val visited = linkedSetOf<String>()
        val pending = ArrayDeque(start)
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (visited.add(name)) pending.addAll(next(name))
        }
        return visited
    }
}
