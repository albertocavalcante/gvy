// SPDX-FileCopyrightText: 2026 Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.testing.api

import com.github.albertocavalcante.gvy.gls.diagnostics.AwaitDiagnosticsParams
import com.google.gson.JsonElement
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

/**
 * Extended language server interface with Groovy-specific custom methods.
 *
 * LSP4J's [Launcher.Builder] needs this interface to properly deserialize responses
 * from custom @JsonRequest methods. When using [LSPLauncher.createClientLauncher],
 * the client only knows the standard [LanguageServer] interface, causing
 * [RemoteEndpoint.request] to return null for custom methods.
 *
 * This interface mirrors the @JsonRequest methods defined on [GroovyLanguageServer]
 * so that the E2E test harness can observe their responses. Existing test-discovery
 * routes retain typed DTOs; other extensions use raw JSON at the SDK boundary.
 *
 * ## Why is this needed?
 *
 * LSP4J uses the `remoteInterface` parameter to determine which methods the remote
 * endpoint supports. Without a typed interface, custom method responses are not
 * properly deserialized.
 *
 * @see [LSP4J JSON-RPC documentation](https://github.com/eclipse-lsp4j/lsp4j/blob/main/documentation/jsonrpc.md)
 * @see [LSP4J extending the protocol](https://github.com/eclipse-lsp4j/lsp4j#extending-the-protocol)
 */
interface GroovyLanguageServerProtocol : LanguageServer {

    /** Optional on older servers; absence is reported by JSON-RPC MethodNotFound. */
    @JsonRequest("groovy/runtimeFacts")
    fun runtimeFacts(): CompletableFuture<JsonElement>

    /** The raw result retains every terminal-analysis field for protocol assertions. */
    @JsonRequest("groovy/awaitDiagnostics")
    fun awaitDiagnostics(params: AwaitDiagnosticsParams?): CompletableFuture<JsonElement>

    /**
     * Discovers all test suites (Spock specifications) in the workspace.
     *
     * Custom LSP method: `groovy/discoverTests`
     *
     * @param params Parameters containing the workspace URI to scan
     * @return List of discovered test suites, each containing test methods
     */
    @JsonRequest("groovy/discoverTests")
    fun discoverTests(params: DiscoverTestsParams): CompletableFuture<List<TestSuite>>

    /**
     * Generates a command to run a specific test or test suite.
     *
     * Custom LSP method: `groovy/runTest`
     *
     * @param params Parameters specifying which test to run
     * @return Command that can be executed to run the test
     */
    @JsonRequest("groovy/runTest")
    fun runTest(params: RunTestParams): CompletableFuture<TestCommand>

    /** Returns the requested AST without duplicating the server's DTO schema. */
    @JsonRequest("groovy/ast")
    fun getAst(params: JsonElement?): CompletableFuture<JsonElement>

    /** Returns the exported index. */
    @JsonRequest("groovy/exportIndex")
    fun exportIndex(params: JsonElement?): CompletableFuture<JsonElement>

    /** Returns build-tool information. */
    @JsonRequest("groovy/getBuildToolInfo")
    fun getBuildToolInfo(params: JsonElement?): CompletableFuture<JsonElement>

    /** Returns coverage information. */
    @JsonRequest("groovy/getCoverage")
    fun getCoverage(params: JsonElement?): CompletableFuture<JsonElement>

    /** Returns test execution results. */
    @JsonRequest("groovy/getTestResults")
    fun getTestResults(params: JsonElement?): CompletableFuture<JsonElement>

    /** Returns workspace dependency information. */
    @JsonRequest("groovy/workspace/dependencies")
    fun getDependencies(params: JsonElement?): CompletableFuture<JsonElement>
}

// ============================================================================
// DTOs - Must match the server-side definitions in TestDiscoveryDtos.kt
// ============================================================================

/**
 * Parameters for the `groovy/discoverTests` custom LSP request.
 */
data class DiscoverTestsParams(val workspaceUri: String)

/**
 * Represents a test suite (e.g., a Spock specification class).
 *
 * @property uri File URI (e.g., "file:///path/to/MySpec.groovy")
 * @property suite Fully qualified class name (e.g., "com.example.MySpec")
 * @property tests List of test methods in this suite
 */
data class TestSuite(val uri: String, val suite: String, val tests: List<Test>)

/**
 * Represents a single test method within a test suite.
 *
 * @property test Method name (e.g., "should calculate sum")
 * @property line 1-indexed line number where the test method starts
 */
data class Test(val test: String, val line: Int)

/**
 * Parameters for the `groovy/runTest` custom LSP request.
 *
 * @property uri File URI of the test class
 * @property suite Fully qualified class name
 * @property test Optional method name (null = run all tests in suite)
 * @property debug Whether to return a debug command
 */
data class RunTestParams(val uri: String, val suite: String, val test: String? = null, val debug: Boolean = false)

/**
 * Command returned by `groovy/runTest` that can be executed to run tests.
 *
 * **IMPORTANT**: Property names must match server-side [TestCommand] in groovy-build-tool module.
 *
 * @property executable The command executable (e.g., "gradle", "./gradlew")
 * @property args Command arguments
 * @property cwd Working directory for command execution
 * @property env Environment variables to set when running the command
 */
data class TestCommand(
    val executable: String,
    val args: List<String>,
    val cwd: String,
    val env: Map<String, String> = emptyMap(),
)
