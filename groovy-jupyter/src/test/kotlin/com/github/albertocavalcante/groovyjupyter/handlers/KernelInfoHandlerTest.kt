package com.github.albertocavalcante.groovyjupyter.handlers

import com.github.albertocavalcante.groovyjupyter.protocol.Header
import com.github.albertocavalcante.groovyjupyter.protocol.JupyterMessage
import com.github.albertocavalcante.groovyjupyter.protocol.MessageType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * TDD tests for KernelInfoHandler - responds to kernel_info_request.
 *
 * kernel_info_request is the first message Jupyter sends to discover
 * kernel capabilities like language, version, and protocol support.
 */
class KernelInfoHandlerTest {

    @Test
    fun `should handle kernel_info_request message type`() {
        // Given: A kernel info handler
        val handler = KernelInfoHandler()

        // Then: Should handle kernel_info_request
        assertThat(handler.canHandle(MessageType.KERNEL_INFO_REQUEST)).isTrue()
    }

    @Test
    fun `should not handle other message types`() {
        // Given: A kernel info handler
        val handler = KernelInfoHandler()

        // Then: Should not handle other types
        assertThat(handler.canHandle(MessageType.EXECUTE_REQUEST)).isFalse()
        assertThat(handler.canHandle(MessageType.SHUTDOWN_REQUEST)).isFalse()
    }

    @Test
    fun `should build valid kernel info reply content`() {
        // Given: A kernel info handler
        val handler = KernelInfoHandler()

        // When: Building kernel info content
        val content = handler.buildKernelInfo()

        // Then: Should contain required fields
        assertThat(content["protocol_version"]).isEqualTo("5.3")
        assertThat(content["implementation"]).isEqualTo("groovy-jupyter")
        assertThat(content["status"]).isEqualTo("ok")
        assertThat(content["banner"]).isNotNull
    }

    @Test
    fun `should include Groovy version in language_info`() {
        // Given: A kernel info handler
        val handler = KernelInfoHandler()

        // When: Building kernel info content
        val content = handler.buildKernelInfo()

        @Suppress("UNCHECKED_CAST")
        val languageInfo = content["language_info"] as Map<String, Any>

        // Then: Should contain Groovy language info
        assertThat(languageInfo["name"]).isEqualTo("groovy")
        assertThat(languageInfo["version"]).isNotNull
        assertThat(languageInfo["mimetype"]).isEqualTo("text/x-groovy")
        assertThat(languageInfo["file_extension"]).isEqualTo(".groovy")
    }

    @Test
    fun `should create kernel_info_reply from request`() {
        // Given: A kernel info request
        val request = JupyterMessage(
            header = Header(
                msgId = "test-id",
                session = "test-session",
                username = "test-user",
                msgType = MessageType.KERNEL_INFO_REQUEST.value,
            ),
        )
        val handler = KernelInfoHandler()

        // When: Creating the reply
        val reply = handler.createReply(request)

        // Then: Reply should have correct structure
        assertThat(reply.header.msgType).isEqualTo(MessageType.KERNEL_INFO_REPLY.value)
        assertThat(reply.parentHeader?.msgId).isEqualTo("test-id")
        assertThat(reply.content["status"]).isEqualTo("ok")
    }
}
