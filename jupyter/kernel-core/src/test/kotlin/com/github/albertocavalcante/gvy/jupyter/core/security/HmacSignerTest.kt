// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jupyter.core.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * TDD tests for HMAC message signing.
 *
 * Jupyter uses HMAC-SHA256 to sign messages for security.
 */
class HmacSignerTest {

    @Test
    fun `should compute HMAC-SHA256 signature`() {
        // Given: A key and message parts
        val signer = HmacSigner("test-key")
        val parts = listOf(
            """{"msg_id":"123"}""",
            """{}""",
            """{}""",
            """{"code":"x=1"}""",
        )

        // When: Computing signature
        val signature = signer.sign(parts)

        // Then: Should produce hex string
        assertThat(signature).isNotEmpty()
        assertThat(signature).matches("[0-9a-f]+")
    }

    @Test
    fun `should produce consistent signatures for same input`() {
        // Given: Same key and parts
        val signer = HmacSigner("consistent-key")
        val parts = listOf("header", "parent", "meta", "content")

        // When: Signing twice
        val sig1 = signer.sign(parts)
        val sig2 = signer.sign(parts)

        // Then: Signatures should match
        assertThat(sig1).isEqualTo(sig2)
    }

    @Test
    fun `should produce different signatures for different content`() {
        // Given: Same key but different parts
        val signer = HmacSigner("same-key")
        val parts1 = listOf("header", "parent", "meta", "content1")
        val parts2 = listOf("header", "parent", "meta", "content2")

        // When: Signing both
        val sig1 = signer.sign(parts1)
        val sig2 = signer.sign(parts2)

        // Then: Signatures should differ
        assertThat(sig1).isNotEqualTo(sig2)
    }

    @Test
    fun `should produce different signatures for different keys`() {
        // Given: Different keys, same parts
        val signer1 = HmacSigner("key-1")
        val signer2 = HmacSigner("key-2")
        val parts = listOf("header", "parent", "meta", "content")

        // When: Signing with both
        val sig1 = signer1.sign(parts)
        val sig2 = signer2.sign(parts)

        // Then: Signatures should differ
        assertThat(sig1).isNotEqualTo(sig2)
    }

    @Test
    fun `should return empty signature when key is empty`() {
        // Given: Empty key (no signing)
        val signer = HmacSigner("")
        val parts = listOf("header", "parent", "meta", "content")

        // When: Signing
        val signature = signer.sign(parts)

        // Then: Should return empty
        assertThat(signature).isEmpty()
    }

    @Test
    fun `should verify valid signature`() {
        // Given: A signed message
        val signer = HmacSigner("verify-key")
        val parts = listOf("header", "parent", "meta", "content")
        val signature = signer.sign(parts)

        // When: Verifying
        val valid = signer.verify(signature, parts)

        // Then: Should be valid
        assertThat(valid).isTrue()
    }

    @Test
    fun `should reject invalid signature`() {
        // Given: A signer and parts
        val signer = HmacSigner("verify-key")
        val parts = listOf("header", "parent", "meta", "content")

        // When: Verifying with wrong signature
        val valid = signer.verify("invalid-signature", parts)

        // Then: Should be invalid
        assertThat(valid).isFalse()
    }

    @Test
    fun `should match known-answer HMAC-SHA256 vector`() {
        // Expected value computed independently with Python:
        //   m = hmac.new(b"secret-key", digestmod=hashlib.sha256)
        //   for p in (b'{"msg_id":"1"}', b"{}", b"{}", b'{"code":"1+1"}'): m.update(p)
        //   m.hexdigest()
        val expected = "f29edc2ae347e99e996cf426a9068abd72bbac08f131d7b7d863f0430600434a"
        val parts = listOf("""{"msg_id":"1"}""", "{}", "{}", """{"code":"1+1"}""")

        assertThat(HmacSigner("secret-key").sign(parts)).isEqualTo(expected)
        assertThat(HmacSigner("secret-key").verify(expected, parts)).isTrue()
    }
}
