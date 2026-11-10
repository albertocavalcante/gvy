package com.github.albertocavalcante.gvy.build.gradle

import org.gradle.tooling.BuildCancelledException
import org.gradle.tooling.BuildException
import org.gradle.tooling.GradleConnectionException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Exception chains mirror what Gradle Tooling API 9.x produces when installing a wrapper distribution:
 * `DistributionFactory.ZippedDistribution` wraps any failure from `org.gradle.wrapper.Install`/`Download`
 * in `GradleConnectionException("Could not install Gradle distribution from '<url>'.")`.
 */
class GradleFailureAnalyzerDistributionDownloadTest {

    private val analyzer = GradleFailureAnalyzer()

    private fun installFailure(cause: Throwable) =
        GradleConnectionException("Could not install Gradle distribution from '$DISTRIBUTION_URL'.", cause)

    private fun httpStatus(code: Int) =
        installFailure(IOException("Server returned HTTP response code: $code for URL: $DISTRIBUTION_URL"))

    @Test
    fun `distribution download read timeout is transient`() {
        val error = installFailure(
            IOException(
                "Downloading from $DISTRIBUTION_URL failed: timeout (10000ms)",
                SocketTimeoutException("Read timed out"),
            ),
        )
        assertTrue(analyzer.isTransient(error))
    }

    @Test
    fun `distribution download timeout message without typed cause is transient`() {
        val error = installFailure(IOException("Downloading from $DISTRIBUTION_URL failed: timeout (10000ms)"))
        assertTrue(analyzer.isTransient(error))
    }

    @ParameterizedTest
    @ValueSource(ints = [408, 429, 500, 502, 503, 504])
    fun `distribution download retryable HTTP status is transient`(code: Int) {
        assertTrue(analyzer.isTransient(httpStatus(code)), "HTTP $code should be retried")
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403, 404, 410, 501, 505])
    fun `distribution download deterministic HTTP status is not transient`(code: Int) {
        assertFalse(analyzer.isTransient(httpStatus(code)), "HTTP $code must not be retried")
    }

    @Test
    fun `distribution download connection reset is transient`() {
        assertTrue(analyzer.isTransient(installFailure(SocketException("Connection reset"))))
    }

    @Test
    fun `distribution download connect timeout is transient`() {
        assertTrue(analyzer.isTransient(installFailure(ConnectException("Operation timed out"))))
    }

    @Test
    fun `distribution download with no route to host is transient`() {
        assertTrue(analyzer.isTransient(installFailure(NoRouteToHostException("No route to host"))))
    }

    @Test
    fun `distribution download with unknown host is transient`() {
        assertTrue(analyzer.isTransient(installFailure(UnknownHostException("services.gradle.org"))))
    }

    @Test
    fun `distribution checksum mismatch is not transient`() {
        val error = installFailure(
            RuntimeException(
                "Verification of Gradle distribution failed!\n\nYour Gradle distribution may have been tampered with.",
            ),
        )
        assertFalse(analyzer.isTransient(error))
    }

    @Test
    fun `distribution TLS handshake failure is not transient`() {
        val error = installFailure(SSLHandshakeException("PKIX path building failed"))
        assertFalse(analyzer.isTransient(error))
    }

    @Test
    fun `nonexistent local distribution is not transient`() {
        val error = IllegalArgumentException("The specified Gradle distribution '$DISTRIBUTION_URL' does not exist.")
        assertFalse(analyzer.isTransient(error))
    }

    @Test
    fun `cancelled distribution download is not transient`() {
        val error =
            BuildCancelledException("Distribution download cancelled. Using distribution from '$DISTRIBUTION_URL'.")
        assertFalse(analyzer.isTransient(error))
    }

    @Test
    fun `build script error is not transient`() {
        val error = BuildException(
            "Could not fetch model of type 'IdeaProject' using Gradle distribution '$DISTRIBUTION_URL'.",
            RuntimeException(
                "A problem occurred evaluating root project 'demo'.",
                RuntimeException("Could not find method implementatio() for arguments [foo:bar:1.0]"),
            ),
        )
        assertFalse(analyzer.isTransient(error))
    }

    @Test
    fun `network failure outside distribution install is not classified by the download rules`() {
        val error = BuildException(
            "Could not fetch model of type 'IdeaProject'.",
            SocketTimeoutException("Read timed out"),
        )
        assertFalse(analyzer.isTransient(error))
    }

    private companion object {
        const val DISTRIBUTION_URL = "https://services.gradle.org/distributions/gradle-9.2.1-bin.zip"
    }
}
