package com.github.albertocavalcante.groovylsp.compilation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

class CentralizedDependencyManagerTest {

    private lateinit var dependencyManager: CentralizedDependencyManager

    @BeforeEach
    fun setUp() {
        dependencyManager = CentralizedDependencyManager()
    }

    @Test
    fun `should notify all listeners when dependencies update`() {
        // Arrange
        val listener1 = TestDependencyListener()
        val listener2 = TestDependencyListener()
        val dependencies = listOf(
            Paths.get("/path/to/junit.jar"),
            Paths.get("/path/to/groovy.jar"),
        )

        dependencyManager.addListener(listener1)
        dependencyManager.addListener(listener2)

        // Act
        dependencyManager.updateDependencies(dependencies)

        // Assert
        assertEquals(dependencies, listener1.lastReceivedDependencies)
        assertEquals(dependencies, listener2.lastReceivedDependencies)
        assertEquals(1, listener1.notificationCount)
        assertEquals(1, listener2.notificationCount)
    }

    @Test
    fun `should immediately notify new listener if dependencies exist`() {
        // Arrange
        val existingDependencies = listOf(Paths.get("/path/to/existing.jar"))
        dependencyManager.updateDependencies(existingDependencies)

        val newListener = TestDependencyListener()

        // Act
        dependencyManager.addListener(newListener)

        // Assert
        assertEquals(existingDependencies, newListener.lastReceivedDependencies)
        assertEquals(1, newListener.notificationCount)
    }

    @Test
    fun `should not notify if dependencies unchanged`() {
        // Arrange
        val listener = TestDependencyListener()
        val dependencies = listOf(Paths.get("/path/to/same.jar"))

        dependencyManager.addListener(listener)
        dependencyManager.updateDependencies(dependencies)

        // Clear notification count after initial update
        listener.notificationCount = 0

        // Act - update with same dependencies
        dependencyManager.updateDependencies(dependencies)

        // Assert - no additional notification should occur
        assertEquals(0, listener.notificationCount)
    }

    @Test
    fun `should notify when dependencies change`() {
        // Arrange
        val listener = TestDependencyListener()
        val initialDependencies = listOf(Paths.get("/path/to/initial.jar"))
        val updatedDependencies = listOf(
            Paths.get("/path/to/initial.jar"),
            Paths.get("/path/to/new.jar"),
        )

        dependencyManager.addListener(listener)
        dependencyManager.updateDependencies(initialDependencies)

        // Clear notification count after initial update
        listener.notificationCount = 0

        // Act
        dependencyManager.updateDependencies(updatedDependencies)

        // Assert
        assertEquals(updatedDependencies, listener.lastReceivedDependencies)
        assertEquals(1, listener.notificationCount)
    }

    @Test
    fun `should handle listener exceptions gracefully`() {
        // Arrange
        val faultyListener = FaultyDependencyListener()
        val goodListener = TestDependencyListener()
        val dependencies = listOf(Paths.get("/path/to/test.jar"))

        dependencyManager.addListener(faultyListener)
        dependencyManager.addListener(goodListener)

        // Act - should not throw exception despite faulty listener
        dependencyManager.updateDependencies(dependencies)

        // Assert - good listener should still receive notification
        assertEquals(dependencies, goodListener.lastReceivedDependencies)
        assertEquals(1, goodListener.notificationCount)
        assertTrue(faultyListener.exceptionThrown)
    }

    @Test
    fun `should clear dependencies and notify listeners`() {
        // Arrange
        val listener = TestDependencyListener()
        val dependencies = listOf(Paths.get("/path/to/test.jar"))

        dependencyManager.addListener(listener)
        dependencyManager.updateDependencies(dependencies)

        // Clear notification count after initial update
        listener.notificationCount = 0

        // Act
        dependencyManager.clearDependencies()

        // Assert
        assertTrue(listener.lastReceivedDependencies!!.isEmpty())
        assertEquals(1, listener.notificationCount)
        assertTrue(dependencyManager.getDependencies().isEmpty())
    }

    private class TestDependencyListener : DependencyListener {
        var lastReceivedDependencies: List<Path>? = null
        var notificationCount = 0

        override fun onDependenciesUpdated(dependencies: List<Path>) {
            lastReceivedDependencies = dependencies
            notificationCount++
        }
    }

    private class FaultyDependencyListener : DependencyListener {
        var exceptionThrown = false

        override fun onDependenciesUpdated(dependencies: List<Path>) {
            exceptionThrown = true
            throw TestException("Test exception")
        }
    }

    private class TestException(message: String) : Exception(message)
}
