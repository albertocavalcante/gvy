package com.github.groovylsp.bsp.resolver

import java.nio.file.Path

/**
 * Represents a single classpath entry with its compiled JAR and optional source JAR.
 *
 * This data class is used to track dependencies and their sources for features like:
 * - Compilation and type resolution
 * - Go-to-definition in external dependencies
 * - Hover documentation extraction
 *
 * @property compiledJar Path to the compiled JAR file (required)
 * @property sourceJar Path to the corresponding source JAR file (optional)
 */
data class ClassPathEntry(val compiledJar: Path, val sourceJar: Path? = null)
