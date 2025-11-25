package com.github.albertocavalcante.groovyjenkins

import org.slf4j.LoggerFactory

/**
 * Result of resolving libraries with warnings for missing ones.
 */
data class LibraryResolutionResult(val resolved: List<SharedLibrary>, val missing: List<LibraryReference>)

/**
 * Resolves library references to configured shared library artifacts.
 */
class SharedLibraryResolver(private val config: JenkinsConfiguration) {
    private val logger = LoggerFactory.getLogger(SharedLibraryResolver::class.java)
    private val librariesByName = config.sharedLibraries.associateBy { it.name }

    /**
     * Resolves a single library reference to its configured artifact.
     * Returns null if the library is not configured.
     */
    fun resolve(reference: LibraryReference): SharedLibrary? {
        val library = librariesByName[reference.name]
        if (library == null) {
            logger.debug("Library '${reference.name}' not found in configuration")
        }
        return library
    }

    /**
     * Resolves multiple library references, returning only successfully resolved ones.
     */
    fun resolveAll(references: List<LibraryReference>): List<SharedLibrary> = references.mapNotNull { resolve(it) }

    /**
     * Resolves multiple library references with detailed result including missing libraries.
     */
    fun resolveAllWithWarnings(references: List<LibraryReference>): LibraryResolutionResult {
        val resolved = mutableListOf<SharedLibrary>()
        val missing = mutableListOf<LibraryReference>()

        references.forEach { ref ->
            val library = resolve(ref)
            if (library != null) {
                resolved.add(library)
            } else {
                missing.add(ref)
                logger.warn("Jenkins shared library '${ref.name}' referenced but not configured")
            }
        }

        return LibraryResolutionResult(resolved, missing)
    }
}
