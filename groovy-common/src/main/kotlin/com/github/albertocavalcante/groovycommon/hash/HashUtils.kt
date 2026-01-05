package com.github.albertocavalcante.groovycommon.hash

import java.security.MessageDigest

/**
 * Utilities for computing cryptographic hashes.
 *
 * Provides functions for:
 * - SHA-256 hashing
 * - Content fingerprinting
 * - Cache key generation
 *
 * All functions are thread-safe and produce deterministic results.
 */

/**
 * Computes the SHA-256 hash of a string.
 *
 * This function:
 * - Uses the SHA-256 algorithm
 * - Returns a hexadecimal string representation (64 characters)
 * - Is deterministic: same input always produces same output
 * - Is thread-safe
 *
 * Common use cases:
 * - Cache key generation
 * - Content fingerprinting
 * - Change detection
 *
 * Examples:
 * - `sha256("hello")` → `"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"`
 * - `sha256("")` → `"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"`
 *
 * @param input The string to hash
 * @return The SHA-256 hash as a lowercase hexadecimal string (64 characters)
 */
fun sha256(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(input.toByteArray())
    return hashBytes.joinToString("") { "%02x".format(it) }
}

/**
 * Computes the SHA-256 hash of a byte array.
 *
 * @param bytes The bytes to hash
 * @return The SHA-256 hash as a lowercase hexadecimal string (64 characters)
 */
fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(bytes)
    return hashBytes.joinToString("") { "%02x".format(it) }
}
