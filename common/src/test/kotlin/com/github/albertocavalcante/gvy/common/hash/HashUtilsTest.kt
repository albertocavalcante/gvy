package com.github.albertocavalcante.gvy.common.hash

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@DisplayName("HashUtils")
class HashUtilsTest {

    @Test
    fun `sha256 produces correct hash for known input`() {
        // Known SHA-256 hash for "hello"
        val expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        assertEquals(expected, sha256("hello"))
    }

    @Test
    fun `sha256 handles empty string`() {
        // Known SHA-256 hash for empty string
        val expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(expected, sha256(""))
    }

    @Test
    fun `sha256 is deterministic`() {
        val input = "test input string"
        val hash1 = sha256(input)
        val hash2 = sha256(input)
        assertEquals(hash1, hash2, "Same input should produce same hash")
    }

    @Test
    fun `sha256 produces different hashes for different inputs`() {
        val hash1 = sha256("input1")
        val hash2 = sha256("input2")
        assertNotEquals(hash1, hash2, "Different inputs should produce different hashes")
    }

    @Test
    fun `sha256 produces 64-character hexadecimal string`() {
        val hash = sha256("any input")
        assertEquals(64, hash.length, "SHA-256 hash should be 64 characters")
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' }, "Hash should contain only hexadecimal characters")
    }

    @Test
    fun `sha256 handles unicode characters`() {
        val input = "Hello 世界 🌍"
        val hash = sha256(input)
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `sha256 handles large strings`() {
        val largeInput = "x".repeat(10000)
        val hash = sha256(largeInput)
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `sha256 handles strings with special characters`() {
        val input = "!@#$%^&*()_+-=[]{}|;':\",./<>?"
        val hash = sha256(input)
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `sha256 is case sensitive`() {
        val hash1 = sha256("Hello")
        val hash2 = sha256("hello")
        assertNotEquals(hash1, hash2, "Hashes should be case-sensitive")
    }

    @Test
    fun `sha256 handles multiline strings`() {
        val input = """
            Line 1
            Line 2
            Line 3
        """.trimIndent()
        val hash = sha256(input)
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `sha256 with byte array produces correct hash`() {
        val bytes = "hello".toByteArray()
        val expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        assertEquals(expected, sha256(bytes))
    }

    @Test
    fun `sha256 with byte array handles empty array`() {
        val bytes = ByteArray(0)
        val expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(expected, sha256(bytes))
    }

    @Test
    fun `sha256 string and byte array produce same hash for same content`() {
        val input = "test content"
        val stringHash = sha256(input)
        val byteHash = sha256(input.toByteArray())
        assertEquals(stringHash, byteHash, "String and byte array hashing should produce same result")
    }
}
