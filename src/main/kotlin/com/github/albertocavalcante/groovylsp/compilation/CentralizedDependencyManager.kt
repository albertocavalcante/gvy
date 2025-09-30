package com.github.albertocavalcante.groovylsp.compilation

import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Centralized dependency manager that maintains a single source of truth for project dependencies.
 * Uses the observer pattern to notify all compilation services when dependencies change.
 *
 * This ensures that both single-file and workspace compilation services always have
 * the same dependency classpath, eliminating the previous issue where dependencies
 * were not properly propagated to WorkspaceCompilationService.
 */
@Suppress("TooGenericExceptionCaught") // Dependency notification needs robust error handling
class CentralizedDependencyManager {
    private val logger = LoggerFactory.getLogger(CentralizedDependencyManager::class.java)

    companion object {
        // Debug output limit for dependency logging
        private const val MAX_DEBUG_DEPENDENCIES_TO_LOG = 10
    }

    // Thread-safe lists for concurrent access
    private val dependencies = mutableListOf<Path>()
    private val listeners = CopyOnWriteArrayList<DependencyListener>()

    /**
     * Updates the global dependency list and notifies all registered listeners.
     * This is the single point where dependencies are updated system-wide.
     */
    @Synchronized
    fun updateDependencies(newDependencies: List<Path>) {
        val changed = dependencies.toSet() != newDependencies.toSet()

        if (changed) {
            dependencies.clear()
            dependencies.addAll(newDependencies)

            logger.info("Dependency update: ${newDependencies.size} JARs")
            if (logger.isDebugEnabled) {
                newDependencies.take(MAX_DEBUG_DEPENDENCIES_TO_LOG).forEach { dep ->
                    logger.debug("  - ${dep.fileName}")
                }
                if (newDependencies.size > MAX_DEBUG_DEPENDENCIES_TO_LOG) {
                    logger.debug("  ... and ${newDependencies.size - MAX_DEBUG_DEPENDENCIES_TO_LOG} more")
                }
            }

            // Notify all listeners of the change
            notifyListeners(newDependencies)
        } else {
            logger.debug("Dependencies unchanged - no notifications sent")
        }
    }

    /**
     * Gets the current dependency list as an immutable copy.
     */
    @Synchronized
    fun getDependencies(): List<Path> = dependencies.toList()

    /**
     * Registers a listener to be notified when dependencies change.
     * If dependencies are already loaded, the listener is immediately notified.
     */
    fun addListener(listener: DependencyListener) {
        listeners.add(listener)
        logger.debug("Added dependency listener: ${listener::class.simpleName}")

        // Immediately notify with current dependencies if available
        val currentDeps = getDependencies()
        if (currentDeps.isNotEmpty()) {
            logger.debug("Immediately notifying new listener with ${currentDeps.size} existing dependencies")
            listener.onDependenciesUpdated(currentDeps)
        }
    }

    /**
     * Removes a listener from future notifications.
     */
    fun removeListener(listener: DependencyListener) {
        val removed = listeners.remove(listener)
        if (removed) {
            logger.debug("Removed dependency listener: ${listener::class.simpleName}")
        }
    }

    /**
     * Gets the number of registered listeners (for debugging).
     */
    fun getListenerCount(): Int = listeners.size

    /**
     * Clears all dependencies and notifies listeners.
     * Used during testing or when starting fresh.
     */
    @Synchronized
    fun clearDependencies() {
        if (dependencies.isNotEmpty()) {
            dependencies.clear()
            logger.info("Cleared all dependencies")
            notifyListeners(emptyList())
        }
    }

    /**
     * Gets debug information about the current state.
     */
    fun getDebugInfo(): String {
        val depCount = getDependencies().size
        val listenerCount = listeners.size
        return "CentralizedDependencyManager[dependencies=$depCount, listeners=$listenerCount]"
    }

    /**
     * Notifies all listeners of dependency changes.
     */
    private fun notifyListeners(newDependencies: List<Path>) {
        logger.debug("Notifying ${listeners.size} listeners of dependency update")

        listeners.forEach { listener ->
            try {
                listener.onDependenciesUpdated(newDependencies)
                logger.trace("Successfully notified ${listener::class.simpleName}")
            } catch (e: IllegalStateException) {
                logger.error("Listener ${listener::class.simpleName} is in invalid state", e)
                // Continue notifying other listeners despite this failure
            } catch (e: UnsupportedOperationException) {
                logger.error("Listener ${listener::class.simpleName} does not support dependency updates", e)
                // Continue notifying other listeners despite this failure
            }
        }
    }
}

/**
 * Interface for objects that need to be notified when dependencies change.
 * Implementations should update their internal state and trigger recompilation if needed.
 */
interface DependencyListener {
    /**
     * Called when the global dependency list changes.
     *
     * @param dependencies The new list of dependency JAR paths
     */
    fun onDependenciesUpdated(dependencies: List<Path>)
}
