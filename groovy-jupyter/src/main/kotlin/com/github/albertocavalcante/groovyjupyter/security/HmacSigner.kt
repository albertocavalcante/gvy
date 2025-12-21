package com.github.albertocavalcante.groovyjupyter.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 message signer for Jupyter protocol.
 *
 * Jupyter uses HMAC signing to authenticate messages between the kernel
 * and frontend. The signature is computed over the concatenation of:
 * header, parent_header, metadata, and content JSON strings.
 *
 * @param key The HMAC key from the connection file. Empty key disables signing.
 *
 * @see <a href="https://jupyter-client.readthedocs.io/en/stable/messaging.html#the-wire-protocol">
 *     Jupyter Wire Protocol</a>
 */
class HmacSigner(private val key: String) {
    private val algorithm = "HmacSHA256"

    /**
     * Sign message parts and return hex-encoded signature.
     *
     * @param parts List of JSON strings: [header, parent_header, metadata, content]
     * @return Hex-encoded signature, or empty string if signing is disabled
     */
    fun sign(parts: List<String>): String {
        if (key.isEmpty()) return ""

        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key.toByteArray(), algorithm))

        // Concatenate all parts
        parts.forEach { mac.update(it.toByteArray()) }

        // Return hex-encoded signature
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }

    /**
     * Verify a signature against message parts.
     *
     * @param signature The hex-encoded signature to verify
     * @param parts List of JSON strings to verify against
     * @return true if signature is valid
     */
    fun verify(signature: String, parts: List<String>): Boolean {
        if (key.isEmpty()) return signature.isEmpty()
        return java.security.MessageDigest.isEqual(sign(parts).toByteArray(), signature.toByteArray())
    }
}
