package com.github.albertocavalcante.gvy.gls.services

import com.github.albertocavalcante.gvy.gls.transport.lsp4j.registerStatusNotificationAdapter
import com.google.gson.JsonParser
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageServer
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatusNotificationTransportTest {

    @Test
    fun `outgoing status notifications preserve independent wire fixtures`() {
        val output = ByteArrayOutputStream()
        val launcher = Launcher.Builder<GroovyLanguageClient>()
            .setLocalService(Any())
            .setRemoteInterface(GroovyLanguageClient::class.java)
            .setInput(ByteArrayInputStream(byteArrayOf()))
            .setOutput(output)
            .configureGson(::registerStatusNotificationAdapter)
            .create()
        Health.entries.forEach { launcher.remoteProxy.groovyStatus(StatusNotification(health = it)) }
        launcher.remoteProxy.groovyStatus(
            StatusNotification(Health.Warning, errorDetails = ToolchainProvisioningError(17, null, null)),
        )
        launcher.remoteProxy.groovyStatus(
            StatusNotification(Health.Error, errorDetails = GenericError("DEPENDENCY_RESOLUTION_FAILED")),
        )
        val wire = output.toString(Charsets.UTF_8)
        val headers = Regex("Content-Length: (\\d+)\\r\\n\\r\\n").findAll(wire).toList()
        val expected = listOf(
            """{"health":"ok","quiescent":true}""",
            """{"health":"warning","quiescent":true}""",
            """{"health":"error","quiescent":true}""",
            """{"health":"warning","quiescent":true,"errorDetails":{"requiredVersion":17,
                "type":"TOOLCHAIN_PROVISIONING_FAILED"}}""",
            """{"health":"error","quiescent":true,"errorDetails":{"errorCode":"DEPENDENCY_RESOLUTION_FAILED",
                "details":{},"suggestions":[],"type":"DEPENDENCY_RESOLUTION_FAILED"}}""",
        )
        assertEquals(expected.size, headers.size)
        headers.forEachIndexed { index, header ->
            val end = headers.getOrNull(index + 1)?.range?.first ?: wire.length
            val body = wire.substring(header.range.last + 1, end)
            assertEquals(header.groupValues[1].toInt(), body.toByteArray(Charsets.UTF_8).size)
            val message = JsonParser.parseString(body).asJsonObject
            assertEquals("groovy/status", message["method"].asString)
            assertEquals(JsonParser.parseString(expected[index]), message["params"])
        }
    }

    private val errors = listOf(
        GradleJdkIncompatibleError("7.0", 21, "8.5", "17", listOf("Upgrade Gradle")),
        NoBuildToolError(listOf("build.gradle"), listOf("Open a project")),
        DependencyResolutionError("Gradle", "Resolution failed", listOf("Inspect the cause")),
        JavaNotFoundError("configured-jdk", listOf("JAVA_HOME")),
        ToolchainProvisioningError(17, "Temurin", "Linux"),
        GroovyJdkIncompatibleError("3.0.9", 21, 65, "4.0.0", "16"),
        ProjectJdkIncompatibleError(17, 21, "build.gradle"),
        ProjectJdkNewerWarning(21, 17, "build.gradle"),
        GenericError("CUSTOM_FAILURE", mapOf("host" to "repository"), listOf("Retry later")),
        GenericError("DEPENDENCY_RESOLUTION_FAILED"),
        DependencyResolutionError("", null),
        ToolchainProvisioningError(null, null, null),
        GenericError(""),
    )

    @Test
    fun `status codec roundtrips typed statuses including generic discriminator collisions`() {
        errors.forEach { error ->
            val sent = StatusNotification(Health.Warning, errorDetails = error)
            assertEquals(sent, StatusNotificationCodec.decode(StatusNotificationCodec.encode(sent)))
        }
    }

    @Test
    fun `all structured error variants survive typed JSON RPC`() {
        val expected = errors.map { error ->
            StatusNotification(Health.Error, true, "Build failed", errorCode = error.type, errorDetails = error)
        }

        val payloads = expected.map(StatusNotificationCodec::encode)
        val received = receive(*payloads.toTypedArray())

        assertEquals(expected, received)
    }

    @Test
    fun `future error kinds retain status health discriminator and generic details`() {
        val received = receive(
            """
                {"health":"warning","quiescent":true,"message":"Future warning","errorCode":"FUTURE",
                 "errorDetails":{"type":"FUTURE","suggestions":["Inspect"],
                                 "details":{"host":"repository"},"class":"untrusted.Name"}}
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                StatusNotification(
                    Health.Warning,
                    true,
                    "Future warning",
                    errorCode = "FUTURE",
                    errorDetails = GenericError("FUTURE", mapOf("host" to "repository"), listOf("Inspect")),
                ),
            ),
            received,
        )
    }

    @Test
    fun `null and malformed details never discard the enclosing error status`() {
        val details = listOf(
            "null",
            "[]",
            "17",
            "{}",
            """{"type":{}}""",
            """{"type":null}""",
            """{"type":"GRADLE_JDK_INCOMPATIBLE"}""",
            """
                {"type":"PROJECT_JDK_INCOMPATIBLE","runningJdkVersion":17.5,
                 "requiredJdkVersion":21,"configurationSource":"build.gradle"}
            """.trimIndent(),
            """{"type":"FUTURE","suggestions":[17]}""",
            """{"type":"FUTURE","details":{"host":[]}}""",
            """{"type":"FUTURE","errorCode":"OTHER"}""",
        )
        val received = receive(
            *details.map { value ->
                """{"health":"error","quiescent":true,"message":"Build failed",
                    "errorCode":"FAILED","errorDetails":$value}"""
            }.toTypedArray(),
        )

        assertEquals(details.size, received.size)
        received.forEach { status ->
            assertEquals(Health.Error, status.health)
            assertEquals("FAILED", status.errorCode)
            assertEquals("Build failed", status.message)
            assertNull(status.errorDetails)
        }
    }

    private fun receive(vararg payloads: String): List<StatusNotification> {
        val client = RecordingClient()
        val messages = payloads.map { payload ->
            """{"jsonrpc":"2.0","method":"groovy/status","params":$payload}"""
        } + """{"jsonrpc":"2.0","id":1,"method":"workspace/applyEdit","params":{"edit":{"changes":{}}}}"""
        val frames = messages.joinToString("") { message ->
            "Content-Length: ${message.toByteArray(Charsets.UTF_8).size}\r\n\r\n$message"
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            ByteArrayInputStream(frames.toByteArray(Charsets.UTF_8)).use { input ->
                ByteArrayOutputStream().use { output ->
                    val launcher = Launcher.Builder<LanguageServer>()
                        .setLocalService(client)
                        .setRemoteInterface(LanguageServer::class.java)
                        .setInput(input)
                        .setOutput(output)
                        .setExecutorService(executor)
                        .configureGson(::registerStatusNotificationAdapter)
                        .create()
                    launcher.startListening().get(10, TimeUnit.SECONDS)
                    assertEquals(1, client.requestsHandled, "Requests after an error status must remain usable")
                    assertEquals(true, output.toString(Charsets.UTF_8).contains("\"applied\":true"))
                }
            }
        } finally {
            executor.shutdownNow()
        }
        return client.statuses
    }

    class RecordingClient : GroovyLanguageClient {
        val statuses = mutableListOf<StatusNotification>()
        var requestsHandled = 0

        override fun groovyStatus(status: StatusNotification) {
            statuses.add(status)
        }

        override fun applyEdit(params: ApplyWorkspaceEditParams): CompletableFuture<ApplyWorkspaceEditResponse> {
            requestsHandled++
            return CompletableFuture.completedFuture(ApplyWorkspaceEditResponse(true))
        }

        override fun telemetryEvent(obj: Any?) = Unit
        override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) = Unit
        override fun showMessage(messageParams: MessageParams) = Unit
        override fun logMessage(message: MessageParams) = Unit
        override fun showMessageRequest(requestParams: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
            CompletableFuture.completedFuture(MessageActionItem("OK"))
    }
}
