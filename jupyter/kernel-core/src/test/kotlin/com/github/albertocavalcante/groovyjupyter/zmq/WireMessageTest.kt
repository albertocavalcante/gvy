package com.github.albertocavalcante.groovyjupyter.zmq

import com.github.albertocavalcante.groovyjupyter.security.HmacSigner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.text.Charsets

/**
 * TDD tests for WireMessage - Jupyter Wire Protocol parsing and serialization.
 *
 * Jupyter messages are multipart ZMQ messages with structure:
 * identities | delimiter | signature | header | parent_header | metadata | content | buffers
 *
 * @see <a href="https://jupyter-client.readthedocs.io/en/stable/messaging.html#the-wire-protocol">
 *     Jupyter Wire Protocol</a>
 */
class WireMessageTest {

    private val delimiter = "<IDS|MSG>".toByteArray(Charsets.UTF_8)

    @Test
    fun `should parse multipart message with single identity`() {
        // Given: A valid wire message with one identity frame
        val identity = "client-uuid".toByteArray()
        val signature = "abc123"
        val header = """{"msg_id":"1","msg_type":"execute_request"}"""
        val parentHeader = "{}"
        val metadata = "{}"
        val content = """{"code":"x=1"}"""

        val frames = listOf(
            identity,
            delimiter,
            signature.toByteArray(),
            header.toByteArray(),
            parentHeader.toByteArray(),
            metadata.toByteArray(),
            content.toByteArray(),
        )

        // When: Parsing the frames
        val wireMsg = WireMessage.fromFrames(frames)

        // Then: Should extract all parts correctly
        assertThat(wireMsg.identities).hasSize(1)
        assertThat(String(wireMsg.identities[0], Charsets.UTF_8)).isEqualTo("client-uuid")
        assertThat(wireMsg.signature).isEqualTo(signature)
        assertThat(wireMsg.header).isEqualTo(header)
        assertThat(wireMsg.parentHeader).isEqualTo(parentHeader)
        assertThat(wireMsg.metadata).isEqualTo(metadata)
        assertThat(wireMsg.content).isEqualTo(content)
        assertThat(wireMsg.buffers).isEmpty()
    }

    @Test
    fun `should parse multipart message with multiple identities`() {
        // Given: A message with multiple identity frames (before delimiter)
        val identity1 = "router-id-1".toByteArray()
        val identity2 = "router-id-2".toByteArray()
        val signature = "sig456"
        val header = """{"msg_id":"2"}"""
        val parentHeader = "{}"
        val metadata = "{}"
        val content = "{}"

        val frames = listOf(
            identity1,
            identity2,
            delimiter,
            signature.toByteArray(),
            header.toByteArray(),
            parentHeader.toByteArray(),
            metadata.toByteArray(),
            content.toByteArray(),
        )

        // When: Parsing
        val wireMsg = WireMessage.fromFrames(frames)

        // Then: Should capture both identities
        assertThat(wireMsg.identities).hasSize(2)
        assertThat(String(wireMsg.identities[0], Charsets.UTF_8)).isEqualTo("router-id-1")
        assertThat(String(wireMsg.identities[1], Charsets.UTF_8)).isEqualTo("router-id-2")
    }

    @Test
    fun `should parse message with no identity frames`() {
        // Given: A message with delimiter as first frame (no identities)
        val signature = "nosig"
        val header = """{"msg_id":"3"}"""
        val parentHeader = "{}"
        val metadata = "{}"
        val content = "{}"

        val frames = listOf(
            delimiter,
            signature.toByteArray(),
            header.toByteArray(),
            parentHeader.toByteArray(),
            metadata.toByteArray(),
            content.toByteArray(),
        )

        // When: Parsing
        val wireMsg = WireMessage.fromFrames(frames)

        // Then: Should have empty identities
        assertThat(wireMsg.identities).isEmpty()
        assertThat(wireMsg.header).isEqualTo(header)
    }

    @Test
    fun `should parse message with extra buffers`() {
        // Given: A message with binary buffers after content
        val identity = "id".toByteArray()
        val buffer1 = byteArrayOf(0x01, 0x02, 0x03)
        val buffer2 = byteArrayOf(0x04, 0x05)

        val frames = listOf(
            identity,
            delimiter,
            "sig".toByteArray(),
            "{}".toByteArray(), // header
            "{}".toByteArray(), // parent
            "{}".toByteArray(), // metadata
            "{}".toByteArray(), // content
            buffer1,
            buffer2,
        )

        // When: Parsing
        val wireMsg = WireMessage.fromFrames(frames)

        // Then: Should capture buffers
        assertThat(wireMsg.buffers).hasSize(2)
        assertThat(wireMsg.buffers[0]).isEqualTo(buffer1)
        assertThat(wireMsg.buffers[1]).isEqualTo(buffer2)
    }

    @Test
    fun `should reject malformed message without delimiter`() {
        // Given: Frames without the delimiter
        val frames = listOf(
            "identity".toByteArray(),
            "not-a-delimiter".toByteArray(),
            "{}".toByteArray(),
        )

        // When/Then: Should throw
        assertThrows<IllegalArgumentException> {
            WireMessage.fromFrames(frames)
        }
    }

    @Test
    fun `should reject message with insufficient frames after delimiter`() {
        // Given: Delimiter but not enough frames after
        val frames = listOf(
            delimiter,
            "sig".toByteArray(),
            "{}".toByteArray(), // header only - need 4 more
        )

        // When/Then: Should throw
        assertThrows<IllegalArgumentException> {
            WireMessage.fromFrames(frames)
        }
    }

    @Test
    fun `should serialize message to frames`() {
        // Given: A WireMessage
        val wireMsg = WireMessage(
            identities = listOf("client-1".toByteArray()),
            signature = "testsig",
            header = """{"msg_id":"1"}""",
            parentHeader = "{}",
            metadata = "{}",
            content = """{"status":"ok"}""",
            buffers = emptyList(),
        )

        // When: Converting to frames
        val frames = wireMsg.toFrames()

        // Then: Should produce correct frame sequence
        assertThat(frames).hasSize(7) // 1 identity + delimiter + 5 message parts
        assertThat(String(frames[0], Charsets.UTF_8)).isEqualTo("client-1")
        assertThat(String(frames[1], Charsets.UTF_8)).isEqualTo("<IDS|MSG>")
        assertThat(String(frames[2], Charsets.UTF_8)).isEqualTo("testsig")
        assertThat(String(frames[3], Charsets.UTF_8)).isEqualTo("""{"msg_id":"1"}""")
        assertThat(String(frames[6], Charsets.UTF_8)).isEqualTo("""{"status":"ok"}""")
    }

    @Test
    fun `should serialize message with buffers`() {
        // Given: A message with binary buffers
        val buffer = byteArrayOf(0x00, 0x01, 0x02)
        val wireMsg = WireMessage(
            identities = emptyList(),
            signature = "",
            header = "{}",
            parentHeader = "{}",
            metadata = "{}",
            content = "{}",
            buffers = listOf(buffer),
        )

        // When: Converting to frames
        val frames = wireMsg.toFrames()

        // Then: Buffer should be at the end
        assertThat(frames).hasSize(7) // delimiter + 5 parts + 1 buffer
        assertThat(frames.last()).isEqualTo(buffer)
    }

    @Test
    fun `should compute signature using HmacSigner`() {
        // Given: A signer and message parts
        val signer = HmacSigner("test-key")
        val wireMsg = WireMessage(
            identities = listOf("id".toByteArray()),
            signature = "", // Will be computed
            header = """{"msg_id":"1"}""",
            parentHeader = "{}",
            metadata = "{}",
            content = """{"code":"1+1"}""",
        )

        // When: Converting to frames with signing
        val frames = wireMsg.toSignedFrames(signer)

        // Then: Signature frame should be valid HMAC
        val signatureFrame = String(frames[2], Charsets.UTF_8)
        assertThat(signatureFrame).isNotEmpty()
        assertThat(signatureFrame).matches("[0-9a-f]+")

        // And: Should verify correctly
        val parts = listOf(
            wireMsg.header,
            wireMsg.parentHeader,
            wireMsg.metadata,
            wireMsg.content,
        )
        assertThat(signer.verify(signatureFrame, parts)).isTrue()
    }

    @Test
    fun `should handle empty signature when key is empty`() {
        // Given: A signer with empty key
        val signer = HmacSigner("")
        val wireMsg = WireMessage(
            identities = emptyList(),
            signature = "",
            header = "{}",
            parentHeader = "{}",
            metadata = "{}",
            content = "{}",
        )

        // When: Converting to signed frames
        val frames = wireMsg.toSignedFrames(signer)

        // Then: Signature should be empty
        val signatureFrame = String(frames[1], Charsets.UTF_8) // After delimiter
        assertThat(signatureFrame).isEmpty()
    }

    @Test
    fun `should roundtrip parse and serialize with binary buffers`() {
        // Given: Original frames including a binary buffer
        val buffer = byteArrayOf(1, 2, 3, 4, 5)
        val original = listOf(
            "identity".toByteArray(Charsets.UTF_8),
            delimiter,
            "signature".toByteArray(Charsets.UTF_8),
            """{"msg_id":"roundtrip"}""".toByteArray(Charsets.UTF_8),
            "{}".toByteArray(Charsets.UTF_8),
            """{"key":"value"}""".toByteArray(Charsets.UTF_8),
            """{"data":123}""".toByteArray(Charsets.UTF_8),
            buffer,
        )

        // When: Parse then serialize
        val wireMsg = WireMessage.fromFrames(original)
        val roundtripped = wireMsg.toFrames()

        // Then: Should match byte-for-byte
        assertThat(roundtripped.size).isEqualTo(original.size)
        roundtripped.zip(original).forEach { (rtFrame, origFrame) ->
            assertThat(rtFrame).isEqualTo(origFrame)
        }
    }

    @Test
    fun `should convert to JupyterMessage with parsed header and content`() {
        // Given: Wire message with properly formatted JSON
        val header =
            """{"msg_id":"abc123","session":"sess1","username":"user","date":"2024-01-01T00:00:00.000Z",""" +
                """"msg_type":"execute_request","version":"5.3"}"""
        val parentHeader = """{}"""
        val metadata = """{"key":"value"}"""
        val content = """{"code":"print('hello')","silent":false}"""

        val frames = listOf(
            "identity1".toByteArray(Charsets.UTF_8),
            WireMessage.DELIMITER.toByteArray(Charsets.UTF_8),
            "signature".toByteArray(Charsets.UTF_8),
            header.toByteArray(Charsets.UTF_8),
            parentHeader.toByteArray(Charsets.UTF_8),
            metadata.toByteArray(Charsets.UTF_8),
            content.toByteArray(Charsets.UTF_8),
        )

        // When: Parse and convert to JupyterMessage
        val wireMsg = WireMessage.fromFrames(frames)
        val jupyterMsg = wireMsg.toJupyterMessage()

        // Then: Should correctly parse fields
        assertThat(jupyterMsg.header.msgId).isEqualTo("abc123")
        assertThat(jupyterMsg.header.session).isEqualTo("sess1")
        assertThat(jupyterMsg.header.msgType).isEqualTo("execute_request")
        assertThat(jupyterMsg.parentHeader).isNull()
        assertThat(jupyterMsg.metadata).containsEntry("key", "value")
        assertThat(jupyterMsg.content).containsEntry("code", "print('hello')")
        assertThat(jupyterMsg.content).containsEntry("silent", false)
        assertThat(jupyterMsg.identities).hasSize(1)
    }

    @Test
    fun `should filter null values from content when parsing JSON`() {
        // Given: Content with null value
        val header = """{"msg_id":"test","session":"sess1","msg_type":"test","version":"5.3"}"""
        val content = """{"name":"Alice","nickname":null,"age":30}"""

        val frames = listOf(
            WireMessage.DELIMITER.toByteArray(Charsets.UTF_8),
            "sig".toByteArray(Charsets.UTF_8),
            header.toByteArray(Charsets.UTF_8),
            "{}".toByteArray(Charsets.UTF_8),
            "{}".toByteArray(Charsets.UTF_8),
            content.toByteArray(Charsets.UTF_8),
        )

        // When
        val jupyterMsg = WireMessage.fromFrames(frames).toJupyterMessage()

        // Then: Null values should be filtered out
        assertThat(jupyterMsg.content).containsEntry("name", "Alice")
        assertThat(jupyterMsg.content).containsEntry("age", 30L)
        assertThat(jupyterMsg.content).doesNotContainKey("nickname")
    }

    @Test
    fun `should preserve string type for string literals that look like booleans or numbers`() {
        // Given: Content with string "true" and string "123"
        val header = """{"msg_id":"test","session":"sess1","msg_type":"test","version":"5.3"}"""
        val content = """{"boolString":"true","numString":"123","realBool":true,"realNum":123}"""

        val frames = listOf(
            WireMessage.DELIMITER.toByteArray(Charsets.UTF_8),
            "sig".toByteArray(Charsets.UTF_8),
            header.toByteArray(Charsets.UTF_8),
            "{}".toByteArray(Charsets.UTF_8),
            "{}".toByteArray(Charsets.UTF_8),
            content.toByteArray(Charsets.UTF_8),
        )

        // When
        val jupyterMsg = WireMessage.fromFrames(frames).toJupyterMessage()

        // Then: String literals should remain strings, not be converted
        assertThat(jupyterMsg.content["boolString"]).isEqualTo("true")
        assertThat(jupyterMsg.content["numString"]).isEqualTo("123")
        assertThat(jupyterMsg.content["realBool"]).isEqualTo(true)
        assertThat(jupyterMsg.content["realNum"]).isEqualTo(123L)
    }
}
