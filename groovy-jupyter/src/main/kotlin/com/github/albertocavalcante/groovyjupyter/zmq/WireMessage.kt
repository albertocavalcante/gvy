package com.github.albertocavalcante.groovyjupyter.zmq

import com.github.albertocavalcante.groovyjupyter.security.HmacSigner
import kotlin.text.Charsets

/**
 * Represents a Jupyter message in wire protocol format.
 *
 * Jupyter messages are sent as multipart ZMQ messages with structure:
 * ```
 * [identity frames...]  ← Router socket identities
 * b'<IDS|MSG>'          ← Delimiter
 * b'signature'          ← HMAC-SHA256 signature (hex)
 * b'header'             ← JSON
 * b'parent_header'      ← JSON
 * b'metadata'           ← JSON
 * b'content'            ← JSON
 * [extra buffers...]    ← Optional binary buffers
 * ```
 *
 * @see <a href="https://jupyter-client.readthedocs.io/en/stable/messaging.html#the-wire-protocol">
 *     Jupyter Wire Protocol</a>
 */
data class WireMessage(
    val identities: List<ByteArray>,
    val signature: String,
    val header: String,
    val parentHeader: String,
    val metadata: String,
    val content: String,
    val buffers: List<ByteArray> = emptyList(),
) {
    /**
     * Convert to ZMQ multipart frames (without recomputing signature).
     */
    fun toFrames(): List<ByteArray> = buildList {
        addAll(identities)
        add(DELIMITER_BYTES)
        add(signature.toByteArray(Charsets.UTF_8))
        add(header.toByteArray(Charsets.UTF_8))
        add(parentHeader.toByteArray(Charsets.UTF_8))
        add(metadata.toByteArray(Charsets.UTF_8))
        add(content.toByteArray(Charsets.UTF_8))
        addAll(buffers)
    }

    /**
     * Convert to ZMQ multipart frames with computed signature.
     */
    fun toSignedFrames(signer: HmacSigner): List<ByteArray> {
        val parts = listOf(header, parentHeader, metadata, content)
        val computedSignature = signer.sign(parts)
        return this.copy(signature = computedSignature).toFrames()
    }

    companion object {
        const val DELIMITER = "<IDS|MSG>"
        private val DELIMITER_BYTES = DELIMITER.toByteArray(Charsets.UTF_8)

        /**
         * Number of required frames after delimiter:
         * signature, header, parent_header, metadata, content
         */
        private const val REQUIRED_FRAMES_AFTER_DELIMITER = 5

        // Frame indices after delimiter
        private const val IDX_SIGNATURE = 0
        private const val IDX_HEADER = 1
        private const val IDX_PARENT_HEADER = 2
        private const val IDX_METADATA = 3
        private const val IDX_CONTENT = 4

        /**
         * Parse ZMQ multipart frames into a WireMessage.
         *
         * @param frames List of byte arrays from ZMQ recv
         * @return Parsed WireMessage
         * @throws IllegalArgumentException if frames are malformed
         */
        fun fromFrames(frames: List<ByteArray>): WireMessage {
            // Find delimiter index
            val delimiterIndex = frames.indexOfFirst { it.contentEquals(DELIMITER_BYTES) }
            require(delimiterIndex >= 0) {
                "Invalid wire message: missing delimiter '$DELIMITER'"
            }

            val framesAfterDelimiter = frames.size - delimiterIndex - 1
            require(framesAfterDelimiter >= REQUIRED_FRAMES_AFTER_DELIMITER) {
                "Invalid wire message: need $REQUIRED_FRAMES_AFTER_DELIMITER frames " +
                    "after delimiter, got $framesAfterDelimiter"
            }

            // Extract parts
            val identities = frames.subList(0, delimiterIndex).toList()
            val afterDelimiter = frames.subList(delimiterIndex + 1, frames.size)

            val signature = String(afterDelimiter[IDX_SIGNATURE], Charsets.UTF_8)
            val header = String(afterDelimiter[IDX_HEADER], Charsets.UTF_8)
            val parentHeader = String(afterDelimiter[IDX_PARENT_HEADER], Charsets.UTF_8)
            val metadata = String(afterDelimiter[IDX_METADATA], Charsets.UTF_8)
            val content = String(afterDelimiter[IDX_CONTENT], Charsets.UTF_8)

            // Any remaining frames are buffers
            val buffers = if (afterDelimiter.size > REQUIRED_FRAMES_AFTER_DELIMITER) {
                afterDelimiter.subList(REQUIRED_FRAMES_AFTER_DELIMITER, afterDelimiter.size).toList()
            } else {
                emptyList()
            }

            return WireMessage(
                identities = identities,
                signature = signature,
                header = header,
                parentHeader = parentHeader,
                metadata = metadata,
                content = content,
                buffers = buffers,
            )
        }
    }
}
