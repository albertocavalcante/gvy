package com.github.albertocavalcante.groovylsp

import com.github.albertocavalcante.groovylsp.compilation.CentralizedDependencyManager
import com.github.albertocavalcante.groovylsp.compilation.GroovyCompilationService

/**
 * Test utilities to reduce duplication in test setup.
 */
object TestUtils {

    /**
     * Creates a GroovyCompilationService with a fresh CentralizedDependencyManager.
     * Use this in test @BeforeEach methods to avoid duplication.
     */
    fun createCompilationService(): GroovyCompilationService {
        val dependencyManager = CentralizedDependencyManager()
        return GroovyCompilationService(dependencyManager)
    }

    /**
     * Creates a CentralizedDependencyManager for tests that need it directly.
     */
    fun createDependencyManager(): CentralizedDependencyManager = CentralizedDependencyManager()
}
