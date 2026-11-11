package com.github.albertocavalcante.gvy.gls.e2e

/** Owns partially acquired session resources until they can be transferred to the session. */
internal class SessionResources private constructor(
    initial: List<AutoCloseable>,
    val interruption: CleanupInterruption,
) : AutoCloseable {
    constructor() : this(emptyList(), CleanupInterruption())

    private val resources = ArrayDeque(initial)

    fun <T : AutoCloseable> own(resource: T): T = resource.also(resources::addLast)

    fun transfer(): SessionResources = SessionResources(resources.toList(), interruption).also { resources.clear() }

    override fun close() {
        if (resources.isEmpty()) return
        val interrupted = Thread.interrupted()
        try {
            closeResources()
        } finally {
            if (interrupted || interruption.observed) Thread.currentThread().interrupt()
        }
    }

    private fun closeResources() {
        val first = resources.removeFirstOrNull() ?: return
        // Nested use closes in reverse acquisition order and preserves suppressed cleanup failures.
        AutoCloseable { interruption.observe { first.close() } }.use { closeResources() }
    }
}

/** Records an interrupted cleanup before another failure can make it a suppressed exception. */
internal class CleanupInterruption {
    var observed: Boolean = false
        private set

    fun <T> observe(action: () -> T): T = try {
        action()
    } catch (failure: InterruptedException) {
        observed = true
        throw failure
    }
}
