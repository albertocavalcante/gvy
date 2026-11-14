// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.build.ArtifactStamp
import com.github.albertocavalcante.gvy.gls.sources.BundledSourceCatalog
import com.github.albertocavalcante.gvy.gls.sources.ResolvedExternalSymbol
import com.github.albertocavalcante.gvy.gls.sources.ResolvedSourceRequest
import com.github.albertocavalcante.gvy.gls.sources.SourceContextIdentity
import com.github.albertocavalcante.gvy.gls.sources.SourceDocumentDescriptor
import com.github.albertocavalcante.gvy.gls.sources.SourceFailure
import com.github.albertocavalcante.gvy.gls.sources.SourceLookupContext
import com.github.albertocavalcante.gvy.gls.sources.SourceLookupPolicy
import com.github.albertocavalcante.gvy.gls.sources.SourceOrigin
import com.github.albertocavalcante.gvy.gls.sources.SourceStoreException
import com.github.albertocavalcante.gvy.gls.sources.copyAndDigest
import com.github.albertocavalcante.gvy.gls.sources.parseJarClassResource
import com.github.albertocavalcante.nativeapi.NativeArtifactStamp
import com.github.albertocavalcante.nativeapi.NativeClasspathInputs
import com.github.albertocavalcante.nativeapi.NativeJdkIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private const val MAX_SOURCE_BINARY_IDENTITY_BYTES = 512L * 1024 * 1024
private const val MAX_SESSION_BINARY_IDENTITY_BYTES = 512L * 1024 * 1024

internal sealed interface SourceRequestSelection {
    data class Selected(val request: ResolvedSourceRequest) : SourceRequestSelection
    data object BinaryAbsent : SourceRequestSelection
    data object Ambiguous : SourceRequestSelection
    data object ContextUnavailable : SourceRequestSelection
    data class Failed(val failure: SourceFailure) : SourceRequestSelection
}

/** A browser request holds one parse and validates every origin selected during that request. */
internal class SourceRequestSession(
    private val selectType: suspend (String) -> SourceRequestSelection,
    private val current: () -> Boolean,
    private val validate: suspend () -> Boolean,
) {
    suspend fun select(binaryName: String): SourceRequestSelection = selectType(binaryName)

    fun isCurrent(): Boolean = current()

    suspend fun validateCurrent(): Boolean = validate()
}

/** Binds source lookup to the native parse and ordered inputs that selected its class resource. */
internal class SourceRequestCapture(
    private val snapshots: CompiledSnapshotAccess,
    private val workspace: WorkspaceManager,
    private val captureInputs: (URI, WorkspaceModel, () -> Unit) -> AdmittedCompilationInputs?,
    private val bundledSources: BundledSourceCatalog = BundledSourceCatalog(),
) {
    // This compatibility entry point keeps the optional snapshot fence beside cancellation and lookup policy.
    @Suppress("LongParameterList")
    suspend fun capture(
        uri: URI,
        binaryName: String,
        memberName: String? = null,
        checkCancelled: () -> Unit = {},
        expectedSnapshot: CompiledDocumentSnapshot? = null,
        policy: SourceLookupPolicy = SourceLookupPolicy.LOCAL_ONLY,
    ): ResolvedSourceRequest? {
        val environment = environment(uri, checkCancelled = checkCancelled) ?: return null
        if (
            expectedSnapshot != null &&
            (
                environment.snapshot.parseResult !== expectedSnapshot.parseResult ||
                    environment.snapshot.sourceText != expectedSnapshot.sourceText
                )
        ) {
            return null
        }
        val selection = select(
            environment,
            binaryName,
            memberName,
            environment.validateCurrent,
            policy,
        ) { origin ->
            environment.validateCurrent() &&
                originFreshlyCurrent(origin, environment) &&
                environment.validateCurrent()
        }
        return when (selection) {
            is SourceRequestSelection.Selected -> selection.request
            is SourceRequestSelection.Failed -> throw ResponseErrorException(
                ResponseError(
                    ResponseErrorCode.RequestFailed,
                    "Dependency class resource could not be verified or read",
                    null,
                ),
            )
            else -> null
        }
    }

    suspend fun recapture(binaryName: String, descriptor: SourceDocumentDescriptor): ResolvedSourceRequest? =
        (select(binaryName, descriptor) as? SourceRequestSelection.Selected)?.request

    suspend fun select(binaryName: String, descriptor: SourceDocumentDescriptor): SourceRequestSelection {
        val uri = descriptor.context.documentUri ?: return SourceRequestSelection.ContextUnavailable
        val environment = environment(uri, descriptor.context) ?: return SourceRequestSelection.ContextUnavailable
        if (!originStronglyCurrent(descriptor.origin, environment)) return SourceRequestSelection.ContextUnavailable
        return select(
            environment,
            binaryName,
            null,
            requestCurrent = {
                environment.validateCurrent() && originCurrent(descriptor.origin, environment.bootJdk)
            },
            validateForPublication = { origin ->
                environment.validateCurrent() &&
                    originFreshlyCurrent(origin, environment) &&
                    environment.validateCurrent()
            },
        )
    }

    suspend fun openSession(
        descriptor: SourceDocumentDescriptor,
        checkCancelled: () -> Unit = {},
    ): SourceRequestSession? {
        val uri = descriptor.context.documentUri ?: return null
        val environment = environment(uri, descriptor.context, checkCancelled) ?: return null
        if (!originStronglyCurrent(descriptor.origin, environment)) return null
        val origins = linkedSetOf(descriptor.origin)
        val isCurrent = {
            environment.isCurrent() &&
                synchronized(origins) { origins.toList() }.all { origin -> originCurrent(origin, environment.bootJdk) }
        }
        if (!isCurrent()) return null
        val validate: suspend () -> Boolean = {
            validateSessionOrigins(origins, environment, isCurrent)
        }
        val selectType: suspend (String) -> SourceRequestSelection = { binaryName ->
            if (!isCurrent()) {
                SourceRequestSelection.ContextUnavailable
            } else {
                when (val selection = select(environment, binaryName, null)) {
                    is SourceRequestSelection.Selected -> {
                        val selectedOrigin = selection.request.symbol.origin
                        val sameArchiveChanged = rememberOrigin(origins, selectedOrigin)
                        if (!sameArchiveChanged && isCurrent()) {
                            SourceRequestSelection.Selected(
                                ResolvedSourceRequest(
                                    selection.request.symbol,
                                    SourceLookupContext(
                                        environment.identity,
                                        validateCurrent = validate,
                                        isCurrent = {
                                            isCurrent() && originCurrent(selectedOrigin, environment.bootJdk)
                                        },
                                    ),
                                ),
                            )
                        } else {
                            SourceRequestSelection.ContextUnavailable
                        }
                    }
                    else -> selection
                }
            }
        }
        return SourceRequestSession(
            selectType = selectType,
            current = isCurrent,
            validate = validate,
        )
    }

    private suspend fun validateSessionOrigins(
        origins: Set<SourceOrigin>,
        environment: CapturedEnvironment,
        isCurrent: () -> Boolean,
    ): Boolean = if (!environment.validateCurrent() || !isCurrent()) {
        false
    } else {
        val selectedOrigins = synchronized(origins) { origins.toList() }
        validateOrigins(selectedOrigins, environment) &&
            synchronized(origins) { origins.toList() == selectedOrigins } &&
            environment.validateCurrent() &&
            isCurrent()
    }

    private fun rememberOrigin(origins: MutableSet<SourceOrigin>, selectedOrigin: SourceOrigin): Boolean =
        synchronized(origins) {
            val previous = origins.filterIsInstance<SourceOrigin.Archive>().firstOrNull { prior ->
                prior.binaryJar.toAbsolutePath().normalize() ==
                    (selectedOrigin as? SourceOrigin.Archive)?.binaryJar?.toAbsolutePath()?.normalize()
            }
            if (
                previous != null &&
                previous.binarySha256 != (selectedOrigin as? SourceOrigin.Archive)?.binarySha256
            ) {
                true
            } else {
                origins.add(selectedOrigin)
                false
            }
        }

    // Each fail-closed exit protects a distinct captured authority; preserve their validation order.
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun environment(
        uri: URI,
        expectedContext: SourceContextIdentity? = null,
        checkCancelled: () -> Unit = {},
    ): CapturedEnvironment? {
        val model = workspace.current()
        // Avoid a workspace-wide admission pass when there is no receipt-bearing compilation to capture.
        val snapshot = snapshots.captureCached(uri) ?: return null
        val admitted = captureInputs(uri, model, checkCancelled) ?: return null
        if (snapshot.admissionFingerprint != admitted.fingerprint) return null
        if (snapshot.parseResult.outcome.achievedPhase < Phases.SEMANTIC_ANALYSIS) return null
        val inputs = snapshot.parseResult.classpathInputs ?: return null
        val bootJdk = inputs.bootJdk ?: return null
        val compileContext = workspace.compileContextFor(uri, snapshot.sourceText, model)
        val sourcePathsCurrent = {
            captureInputs(uri, workspace.current(), checkCancelled)?.fingerprint == snapshot.admissionFingerprint
        }
        if (compileContext.classpath != inputs.classpath || admitted.sourceRoots != inputs.sourceRoots) return null
        if (compileContext.fingerprint != snapshot.configurationFingerprint) return null
        if (
            inputs.classpathStamps.any { it == null } ||
            !classpathCurrent(inputs, includeDirectories = true, checkCancelled = checkCancelled)
        ) {
            return null
        }
        val identity = identity(uri, compileContext, inputs, admitted.fingerprint)
        if (expectedContext != null && identity != expectedContext) return null
        val environmentCurrent = {
            snapshots.isCurrent(uri, snapshot) &&
                workspace.compileContextFor(uri, snapshot.sourceText).let { currentContext ->
                    currentContext.fingerprint == compileContext.fingerprint &&
                        currentContext.classpath == inputs.classpath
                } &&
                workspace.current().sourceRoots == model.sourceRoots &&
                classpathCurrent(inputs, includeDirectories = false, checkCancelled = checkCancelled)
        }
        val validateCurrent = {
            environmentCurrent() &&
                sourcePathsCurrent() &&
                classpathCurrent(inputs, includeDirectories = true, checkCancelled = checkCancelled) &&
                environmentCurrent()
        }
        return CapturedEnvironment(
            snapshot,
            compileContext,
            identity,
            environmentCurrent,
            validateCurrent,
            bootJdk,
            checkCancelled,
        )
            .takeIf { it.isCurrent() }
    }

    // Keep lookup guards explicit, including rechecks after class-loader and archive I/O.
    @Suppress("ReturnCount", "LongParameterList")
    private suspend fun select(
        environment: CapturedEnvironment,
        binaryName: String,
        memberName: String?,
        requestCurrent: () -> Boolean = environment.isCurrent,
        policy: SourceLookupPolicy = SourceLookupPolicy.LOCAL_ONLY,
        validateForPublication: suspend (SourceOrigin) -> Boolean = { origin ->
            requestCurrent() && originCurrent(origin, environment.bootJdk)
        },
    ): SourceRequestSelection {
        if (!validBinaryName(binaryName) || !environment.isCurrent()) return SourceRequestSelection.ContextUnavailable
        val loader = environment.snapshot.parseResult.compilationUnit.classLoader
        val resources = try {
            classResources(loader, binaryName)
        } catch (_: IOException) {
            return SourceRequestSelection.Failed(SourceFailure.IO_FAILURE)
        } catch (_: URISyntaxException) {
            return SourceRequestSelection.Failed(SourceFailure.IO_FAILURE)
        }
        selectionWithoutUniqueResource(resources.size, environment.isCurrent)?.let { return it }
        val (selectedName, resource) = resources.single()
        val origin = try {
            origin(resource, selectedName, environment)
        } catch (failure: SourceStoreException) {
            return SourceRequestSelection.Failed(failure.failure)
        } catch (_: IOException) {
            return SourceRequestSelection.Failed(SourceFailure.IO_FAILURE)
        } catch (_: SecurityException) {
            return SourceRequestSelection.Failed(SourceFailure.IO_FAILURE)
        } ?: return SourceRequestSelection.ContextUnavailable
        val context = SourceLookupContext(
            environment.identity,
            policy = policy,
            isCurrent = { requestCurrent() && originCurrent(origin, environment.bootJdk) },
            validateCurrent = { validateForPublication(origin) },
        )
        if (!environment.isCurrent() || !originCurrent(origin, environment.bootJdk)) {
            return SourceRequestSelection.ContextUnavailable
        }
        val symbol = ResolvedExternalSymbol(selectedName, origin, memberName)
        return SourceRequestSelection.Selected(ResolvedSourceRequest(symbol, context))
    }

    // A captured environment is one receipt and its distinct fast/full validators, never independently optional.
    @Suppress("LongParameterList")
    private class CapturedEnvironment(
        val snapshot: CompiledDocumentSnapshot,
        val compileContext: DocumentCompileContext,
        val identity: SourceContextIdentity,
        val isCurrent: () -> Boolean,
        val validateCurrent: () -> Boolean,
        val bootJdk: NativeJdkIdentity,
        val checkCancelled: () -> Unit,
    ) {
        val archiveDigests = mutableMapOf<Path, String>()
    }

    private fun classpathCurrent(
        inputs: NativeClasspathInputs,
        includeDirectories: Boolean,
        checkCancelled: () -> Unit,
    ): Boolean {
        if (!compilerSdkCurrent(inputs, checkCancelled)) return false
        return if (includeDirectories) {
            val observed = NativeClasspathInputs.capture(inputs.classpath, emptyList(), checkCancelled)
            observed.classpathStamps == inputs.classpathStamps && observed.bootJdk == inputs.bootJdk
        } else {
            NativeJdkIdentity.observe(checkCancelled) == inputs.bootJdk &&
                inputs.classpath.indices.all { index ->
                    checkCancelled()
                    val expected = inputs.classpathStamps[index] ?: return@all false
                    expected.files != null || NativeArtifactStamp.observe(
                        inputs.classpath[index],
                        maxPaths = 1,
                        checkCancelled = checkCancelled,
                    ) == expected
                }
        }
    }

    private suspend fun origin(resource: URI, binaryName: String, environment: CapturedEnvironment): SourceOrigin? {
        bundledSources.selectedOrigin(resource, binaryName)?.let { return it }
        val archive = parseJarClassResource(resource)
        return when {
            archive != null -> {
                if (archive.entryName == binaryName.replace('.', '/') + ".class") {
                    archiveOrigin(archive.archive, environment)
                } else {
                    null
                }
            }
            resource.scheme == "jrt" ->
                resource.path
                    ?.trimStart('/')
                    ?.substringBefore('/')
                    ?.takeIf { it.isNotBlank() }
                    ?.let { SourceOrigin.Jdk(environment.bootJdk.imageHome, it) }
            else -> SourceOrigin.Unknown(resource)
        }
    }

    private suspend fun archiveOrigin(archive: Path, environment: CapturedEnvironment): SourceOrigin.Archive? {
        val selectedSnapshot = (environment.compileContext.selection as? TargetSelection.Selected)?.snapshot
        val artifacts = selectedSnapshot?.artifacts.orEmpty()
            .filter { it.path.toAbsolutePath().normalize() == archive }
            .distinct()
        if (artifacts.size > 1) return null
        val observed = ArtifactStamp.of(archive) ?: return null
        val reported = artifacts.singleOrNull()
        return if (reported?.stamp != null && reported.stamp != observed) {
            null
        } else {
            val binarySha256 = archiveDigest(environment, archive)
            SourceOrigin.Archive(archive, reported?.coordinates, observed, binarySha256)
        }
    }

    private suspend fun archiveDigest(
        environment: CapturedEnvironment,
        path: Path,
        forceFresh: Boolean = false,
        maxBytes: Long = MAX_SOURCE_BINARY_IDENTITY_BYTES,
    ): String {
        val normalized = path.toAbsolutePath().normalize()
        if (!forceFresh) {
            synchronized(environment.archiveDigests) { environment.archiveDigests[normalized] }?.let { return it }
        }
        val observed = runInterruptible(Dispatchers.IO) {
            environment.checkCancelled()
            if (!Files.isRegularFile(normalized)) throw IOException("Selected binary archive is unavailable")
            Files.newInputStream(normalized).use { input -> copyAndDigest(input, maxBytes) }
        }
        environment.checkCancelled()
        return synchronized(environment.archiveDigests) {
            if (forceFresh) {
                environment.archiveDigests[normalized] = observed
                observed
            } else {
                environment.archiveDigests.getOrPut(normalized) { observed }
            }
        }
    }

    private suspend fun originStronglyCurrent(origin: SourceOrigin, environment: CapturedEnvironment): Boolean = try {
        if (!originCurrent(origin, environment.bootJdk)) {
            false
        } else {
            when (origin) {
                is SourceOrigin.Archive ->
                    origin.binarySha256 != null &&
                        archiveDigest(environment, origin.binaryJar).equals(origin.binarySha256, ignoreCase = true)
                else -> true
            }
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private suspend fun originFreshlyCurrent(origin: SourceOrigin, environment: CapturedEnvironment): Boolean = try {
        if (!originCurrent(origin, environment.bootJdk)) {
            false
        } else {
            val current = when (origin) {
                is SourceOrigin.Archive ->
                    origin.binarySha256 != null &&
                        archiveDigest(environment, origin.binaryJar, forceFresh = true)
                            .equals(origin.binarySha256, ignoreCase = true)
                else -> true
            }
            current && originCurrent(origin, environment.bootJdk)
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: SourceStoreException) {
        false
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    // Stop at the first changed origin; later archive reads must not run after a failed budget or identity fence.
    @Suppress("ReturnCount")
    private suspend fun validateOrigins(origins: List<SourceOrigin>, environment: CapturedEnvironment): Boolean {
        return try {
            val archivesByPath = origins.filterIsInstance<SourceOrigin.Archive>()
                .groupBy { it.binaryJar.toAbsolutePath().normalize() }
            val archiveSizes = mutableMapOf<Path, Long>()
            var totalArchiveBytes = 0L
            for (path in archivesByPath.keys) {
                environment.checkCancelled()
                val size = runInterruptible(Dispatchers.IO) { Files.size(path) }
                if (size < 0 || size > MAX_SESSION_BINARY_IDENTITY_BYTES - totalArchiveBytes) return false
                archiveSizes[path] = size
                totalArchiveBytes += size
            }

            for (origin in origins) {
                environment.checkCancelled()
                if (!originCurrent(origin, environment.bootJdk)) return false
            }

            for ((path, archives) in archivesByPath) {
                environment.checkCancelled()
                val expectedSha256 = archives.map { it.binarySha256?.lowercase() ?: return false }
                    .distinct()
                    .singleOrNull() ?: return false
                if (
                    !archiveDigest(
                        environment,
                        path,
                        forceFresh = true,
                        maxBytes = archiveSizes.getValue(path),
                    ).equals(expectedSha256, ignoreCase = true)
                ) {
                    return false
                }
                if (archives.any { !originCurrent(it, environment.bootJdk) }) return false
            }
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun originCurrent(origin: SourceOrigin, bootJdk: NativeJdkIdentity): Boolean = when (origin) {
        is SourceOrigin.Archive ->
            origin.observedStamp != null && ArtifactStamp.of(origin.binaryJar) == origin.observedStamp
        is SourceOrigin.Jdk -> origin.imageHome == bootJdk.imageHome
        else -> true
    }

    private fun identity(
        uri: URI,
        context: DocumentCompileContext,
        inputs: NativeClasspathInputs,
        admissionFingerprint: String,
    ): SourceContextIdentity {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        add(uri.toString())
        add(context.fingerprint)
        add("source-admission")
        add(admissionFingerprint)
        add(inputs.classpath.size.toString())
        inputs.classpath.forEachIndexed { index, path ->
            add(path.toString())
            val stamp = checkNotNull(inputs.classpathStamps[index])
            add(stamp.size.toString())
            add(stamp.lastModifiedMillis.toString())
            add(stamp.files?.toString() ?: "file")
            add(stamp.observedPaths.toString())
        }
        add(inputs.sourceRoots.size.toString())
        inputs.sourceRoots.forEach { add(it.toString()) }
        val sdk = inputs.compilerSdk
        add(if (sdk == null) "no-compiler-sdk" else "compiler-sdk")
        sdk?.let { compilerSdk ->
            add(compilerSdk.componentId)
            add(compilerSdk.container.toString())
            add(compilerSdk.indexDigest)
            add(compilerSdk.containerStamp.size.toString())
            add(compilerSdk.containerStamp.lastModifiedMillis.toString())
            add(compilerSdk.containerStamp.observedPaths.toString())
        }
        val bootJdk = checkNotNull(inputs.bootJdk)
        add("boot-jdk")
        add(bootJdk.imageHome.toString())
        add(bootJdk.runtimeVersion)
        add(bootJdk.vendor)
        add(bootJdk.modulesStamp.size.toString())
        add(bootJdk.modulesStamp.lastModifiedMillis.toString())
        add(bootJdk.modulesStamp.observedPaths.toString())
        val id = digest.digest().joinToString("") { "%02x".format(it) }
        return SourceContextIdentity("native:$id", uri, context.fingerprint)
    }
}

private const val MAX_BINARY_NAME = 4096
private const val MAX_NAME_SEGMENTS = 64

private fun validBinaryName(name: String): Boolean = name.isNotBlank() &&
    name.length <= MAX_BINARY_NAME &&
    name.count { it == '.' } < MAX_NAME_SEGMENTS &&
    name.split('.').all(::validIdentifier)

private fun binaryCandidates(name: String): List<String> = buildList {
    add(name)
    var candidate = name
    var separator = candidate.lastIndexOf('.')
    while (separator >= 0) {
        candidate = candidate.substring(0, separator) + '$' + candidate.substring(separator + 1)
        add(candidate)
        separator = candidate.lastIndexOf('.')
    }
}

private fun validIdentifier(segment: String): Boolean {
    if (segment.isEmpty()) return false
    val first = segment.codePointAt(0)
    if (!Character.isJavaIdentifierStart(first)) return false
    var offset = Character.charCount(first)
    while (offset < segment.length) {
        val codePoint = segment.codePointAt(offset)
        if (!Character.isJavaIdentifierPart(codePoint)) return false
        offset += Character.charCount(codePoint)
    }
    return true
}

private fun classResources(loader: ClassLoader, binaryName: String): List<Pair<String, URI>> =
    binaryCandidates(binaryName).mapNotNull { candidate ->
        val resource =
            loader.getResource(candidate.replace('.', '/') + ".class")?.toURI()
                ?: return@mapNotNull null
        candidate to resource
    }

private fun compilerSdkCurrent(inputs: NativeClasspathInputs, checkCancelled: () -> Unit): Boolean {
    val sdk = inputs.compilerSdk ?: return true
    if (sdk.container.scheme != "file" || sdk.containerStamp.files != null) return false
    val container =
        try {
            Path.of(sdk.container)
        } catch (_: IllegalArgumentException) {
            null
        }
    return container != null &&
        NativeArtifactStamp.observe(container, maxPaths = 1, checkCancelled = checkCancelled) == sdk.containerStamp
}

private fun selectionWithoutUniqueResource(count: Int, isCurrent: () -> Boolean): SourceRequestSelection? = when {
    count == 1 -> null
    !isCurrent() -> SourceRequestSelection.ContextUnavailable
    count == 0 -> SourceRequestSelection.BinaryAbsent
    else -> SourceRequestSelection.Ambiguous
}
