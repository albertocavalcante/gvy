package com.github.albertocavalcante.groovyjupyter.protocol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * TDD tests for Jupyter message serialization.
 *
 * Jupyter messages follow a specific wire protocol with header, parent_header,
 * metadata, and content sections.
 */
class JupyterMessageTest {

    @Test
    fun `should create message with type and session`() {
        // Given: A message type
        val type = MessageType.EXECUTE_REQUEST
        val sessionId = "test-session-123"

        // When: Creating a message
        val message = JupyterMessage.create(type, sessionId)

        // Then: Header should be populated
        assertThat(message.header.msgType).isEqualTo(MessageType.EXECUTE_REQUEST.value)
        assertThat(message.header.session).isEqualTo(sessionId)
        assertThat(message.header.msgId).isNotEmpty()
        assertThat(message.header.date).isNotEmpty()
    }

    @Test
    fun `should create reply from request`() {
        // Given: A request message
        val request = JupyterMessage.create(MessageType.EXECUTE_REQUEST, "session-1")
        request.content = mapOf("code" to "println 'hello'")

        // When: Creating a reply
        val reply = request.createReply(MessageType.EXECUTE_REPLY)

        // Then: Reply should link to request via parent header
        assertThat(reply.parentHeader).isEqualTo(request.header)
        assertThat(reply.header.msgType).isEqualTo(MessageType.EXECUTE_REPLY.value)
        assertThat(reply.header.session).isEqualTo(request.header.session)
    }

    @Test
    fun `should serialize header to JSON`() {
        // Given: A message header
        val header = Header(
            msgId = "msg-123",
            session = "session-456",
            username = "kernel",
            date = "2024-01-01T00:00:00.000Z",
            msgType = "execute_request",
            version = "5.3",
        )

        // When: Serializing to JSON
        val json = header.toJson()

        // Then: JSON should contain all fields with correct names
        assertThat(json).contains("\"msg_id\"")
        assertThat(json).contains("\"msg-123\"")
        assertThat(json).contains("\"msg_type\"")
        assertThat(json).contains("\"execute_request\"")
    }

    @Test
    fun `should have correct message type values`() {
        // Then: Message types should match Jupyter protocol
        assertThat(MessageType.EXECUTE_REQUEST.value).isEqualTo("execute_request")
        assertThat(MessageType.EXECUTE_REPLY.value).isEqualTo("execute_reply")
        assertThat(MessageType.EXECUTE_RESULT.value).isEqualTo("execute_result")
        assertThat(MessageType.STATUS.value).isEqualTo("status")
        assertThat(MessageType.STREAM.value).isEqualTo("stream")
        assertThat(MessageType.KERNEL_INFO_REQUEST.value).isEqualTo("kernel_info_request")
        assertThat(MessageType.KERNEL_INFO_REPLY.value).isEqualTo("kernel_info_reply")
    }

    @Test
    fun `should serialize content map to JSON`() {
        // Given: A message with content
        val message = JupyterMessage.create(MessageType.STREAM, "session-1")
        message.content = mapOf(
            "name" to "stdout",
            "text" to "Hello, World!\n",
        )

        // When: Serializing content
        val json = message.contentToJson()

        // Then: JSON should be valid
        assertThat(json).contains("\"name\"")
        assertThat(json).contains("\"stdout\"")
        assertThat(json).contains("\"text\"")
        assertThat(json).contains("Hello, World!")
    }

    @Test
    fun `should generate unique message IDs`() {
        // When: Creating multiple messages
        val msg1 = JupyterMessage.create(MessageType.STATUS, "session-1")
        val msg2 = JupyterMessage.create(MessageType.STATUS, "session-1")

        // Then: Each should have a unique ID
        assertThat(msg1.header.msgId).isNotEqualTo(msg2.header.msgId)
    }
}
