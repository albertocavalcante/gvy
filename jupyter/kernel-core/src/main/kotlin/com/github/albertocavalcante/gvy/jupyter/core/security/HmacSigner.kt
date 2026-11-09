package com.github.albertocavalcante.gvy.jupyter.core.security

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

    /** False when the key is empty, meaning messages are neither signed nor authenticated. */
    val isEnabled: Boolean get() = key.isNotEmpty()

    /**
     * Sign message parts and return hex-encoded signature.
     *
     * @param parts List of JSON strings: [header, parent_header, metadata, content]
     * @return Hex-encoded signature, or empty string if signing is disabled
     */
    fun sign(parts: List<String>): String = signBytes(parts.map { it.toByteArray(Charsets.UTF_8) })

    /**
     * Sign raw message parts (exact wire bytes) and return the hex-encoded signature.
     *
     * Use this when verifying received messages: decoding to a String and re-encoding is not byte-exact for
     * input that is not canonical UTF-8.
     */
    fun signBytes(parts: List<ByteArray>): String {
        if (key.isEmpty()) return ""

        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), algorithm))
        parts.forEach { mac.update(it) }
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

    /** Verify a signature against the exact wire bytes of the message parts. */
    fun verifyBytes(signature: String, parts: List<ByteArray>): Boolean {
        if (key.isEmpty()) return signature.isEmpty()
        return java.security.MessageDigest.isEqual(
            signBytes(parts).toByteArray(Charsets.UTF_8),
            signature.toByteArray(Charsets.UTF_8),
        )
    }
}
