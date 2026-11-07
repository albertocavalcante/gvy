package com.github.albertocavalcante.gvy.semantics.db

/** Instantiated class contexts for a single concrete viewpoint, using only detached direct-edge facts. */
internal class ImplementationBindings(
    private val hierarchy: Map<String, ImplementationType>,
    viewpoint: String,
    private val edgeVisited: (() -> Unit)? = null,
) {
    // Null means conflicting/unknown bindings; a later path must never replace that with a guessed answer.
    private val contexts = mutableMapOf<String, Map<String, ImplementationTypeReference>?>()

    init {
        contexts[viewpoint] = defaults(viewpoint)
        val pending = ArrayDeque(listOf(viewpoint))
        while (pending.isNotEmpty()) {
            val owner = pending.removeFirst()
            val type = hierarchy[owner]
            val parents = listOfNotNull(type?.superclass) + type?.interfaces.orEmpty()
            parents.forEach { parent ->
                edgeVisited?.invoke()
                val bindings = contexts[owner]?.let { parentContext(owner, parent, it) }
                val previous = contexts[parent]
                if (parent !in contexts || previous != null && previous != bindings) {
                    contexts[parent] = if (parent in contexts) null else bindings
                    pending.add(parent)
                }
            }
        }
    }

    fun parameters(owner: String, method: ImplementationMethod): List<ImplementationTypeReference>? {
        val context = contexts[owner] ?: return null
        val references = method.parameterReferences.takeIf { it.isNotEmpty() }
            ?: return method.parameterTypes.map(::fromErasure)
        val resolved = references.map { resolve(it, context) }
        return resolved.filterNotNull().takeIf { it.size == references.size }
    }

    private fun parentContext(
        owner: String,
        parent: String,
        context: Map<String, ImplementationTypeReference>,
    ): Map<String, ImplementationTypeReference>? {
        val target = defaults(parent) ?: return null
        val arguments = hierarchy[owner]?.parentArguments?.get(parent) ?: return target
        if (arguments.size != target.size) return null
        val resolved = arguments.map { resolve(it, context) }
        return resolved.filterNotNull().takeIf { it.size == arguments.size }?.let { target.keys.zip(it).toMap() }
    }

    private fun defaults(owner: String): Map<String, ImplementationTypeReference>? =
        hierarchy[owner]?.parameterDefaults?.mapValues { (_, type) -> fromErasure(type) }

    private fun resolve(
        reference: ImplementationTypeReference,
        context: Map<String, ImplementationTypeReference>,
    ): ImplementationTypeReference? {
        val element = if (reference.isVariable) context[reference.name] else ImplementationTypeReference(reference.name)
        return element?.takeIf { reference.dimensions <= Int.MAX_VALUE - it.dimensions }
            ?.copy(dimensions = element.dimensions + reference.dimensions)
    }

    private fun fromErasure(name: String): ImplementationTypeReference {
        var end = name.length
        var dimensions = 0
        while (end >= 2 && name.substring(end - 2, end) == "[]") {
            end -= 2
            dimensions++
        }
        return ImplementationTypeReference(name.substring(0, end), dimensions)
    }
}
