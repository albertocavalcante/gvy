package com.github.albertocavalcante.groovylsp.buildtool.gradle

/**
 * Exception thrown when the JDK running the LSP is incompatible with the project's Gradle version.
 *
 * This is a fatal error that cannot be resolved by retrying or using an isolated Gradle user home.
 * The user must either upgrade their Gradle wrapper or run the LSP with a compatible JDK version.
 */
class GradleJdkIncompatibleException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
