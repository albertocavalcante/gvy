// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import com.github.albertocavalcante.gvy.build.MavenSourceArtifactResolver
import com.github.albertocavalcante.gvy.build.SourceArtifactResolver
import com.github.albertocavalcante.gvy.build.VerifiedSourceArtifactRequest
import com.github.albertocavalcante.gvy.build.VerifiedSourceArtifactResolver
import com.github.albertocavalcante.gvy.build.VerifiedSourceArtifactResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val MAX_DECLARATION_INDEXES = 128
private const val MAX_INSPECTED_SOURCE_BYTES = 8L * 1024 * 1024
private const val MAX_SOURCE_INPUTS = 64
private const val MAX_BINARY_IDENTITY_BYTES = 512L * 1024 * 1024
private const val SOURCE_MISS_TTL_NANOS = 3_000_000_000L

/** Sources follow the request's actual binary origin; missing provenance never becomes guessed metadata. */
// Injectable IO dependencies and navigation entry points are grouped around shared cache and lifecycle state.
@Suppress("LongParameterList", "TooManyFunctions")
class SourceNavigationService(
    private val sourceResolver: SourceArtifactResolver = MavenSourceArtifactResolver(),
    sourceCacheRoot: Path = defaultSourceCacheRoot(),
    private val jdkSourceResolver: JdkSourceResolver = JdkSourceResolver(),
    private val javaSourceInspector: JavaSourceInspector = JavaSourceInspector(),
    private val sourcePresentation: SourcePresentation = PortableSourcePresentation(
        sourceCacheRoot.resolve("views-v2"),
    ),
    private val bundledSources: BundledSourceCatalog = BundledSourceCatalog(),
    private val attachmentStore: SourceAttachmentStore = SourceAttachmentStore(
        sourceCacheRoot.resolve("attachments-v2"),
        bundledSources,
    ),
    private val contentStoreFactory: (Path) -> SourceContentStore = { SourceContentStore(it) },
) : SourceNavigator,
    AutoCloseable {
    private val contentRoot = sourceCacheRoot.resolve("content-v2")
    private val storeInitialization = Mutex()
    private var store: SourceContentStore? = null
    private val acquisitions = SourceRequestCoalescer<AcquisitionKey, InputResult>()
    private val inspections = SourceRequestCoalescer<DefinitionKey, SourceDefinition?>()
    private val closed = AtomicBoolean()
    private val groovySourceInspector = GroovySourceDeclarationInspector()
    private val monitor = Any()
    private val definitions = linkedMapOf<DefinitionKey, CachedDefinition>()
    private val inputs = linkedMapOf<InputKey, CachedInput>()
    private val inspectionCount = AtomicLong()
    private val retrySequence = AtomicLong()

    override suspend fun retrySource(symbol: ResolvedExternalSymbol, context: SourceLookupContext): SourceLookupResult {
        synchronized(monitor) {
            ensureOpen()
            inputs.keys.removeIf { key ->
                originReference(key.origin) == originReference(symbol.origin) && key.context == context.identity.id
            }
        }
        return locateInternal(symbol, context, retrySequence.incrementAndGet())
    }

    override suspend fun locate(symbol: ResolvedExternalSymbol, context: SourceLookupContext): SourceLookupResult =
        locateInternal(symbol, context, 0)

    private suspend fun locateInternal(
        symbol: ResolvedExternalSymbol,
        context: SourceLookupContext,
        attempt: Long,
    ): SourceLookupResult {
        currentCoroutineContext().ensureActive()
        ensureOpen()
        if (!context.isCurrent()) return SourceLookupResult.Stale
        return try {
            val selectedSymbol = withBinaryIdentity(symbol)
            if (!context.isCurrent()) return SourceLookupResult.Stale
            val attachmentRevision = attachmentStore.revision(selectedSymbol.origin, context.identity)
            val input = selectInput(selectedSymbol, context, attachmentRevision, attempt)
            val result = when (input) {
                is InputResult.Available -> locateInArchive(selectedSymbol, context, input.input, input.prefix)
                is InputResult.Unavailable -> SourceLookupResult.Unavailable(input.reason)
                is InputResult.Failed -> SourceLookupResult.Failed(input.failure)
            }
            currentCoroutineContext().ensureActive()
            ensureOpen()
            val sameAttachment = attachmentStore.revision(selectedSymbol.origin, context.identity) == attachmentRevision
            val current =
                sameAttachment &&
                    context.isCurrent() &&
                    context.validateCurrent() &&
                    context.isCurrent()
            currentCoroutineContext().ensureActive()
            ensureOpen()
            if (current) {
                result
            } else {
                SourceLookupResult.Stale
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SourceStoreException) {
            failed(context, failure.failure)
        } catch (_: IOException) {
            failed(context, SourceFailure.IO_FAILURE)
        } catch (_: SecurityException) {
            failed(context, SourceFailure.IO_FAILURE)
        }
    }

    @Suppress("ReturnCount") // Reject invalid attachments before mutating the store.
    override suspend fun attachSource(
        symbol: ResolvedExternalSymbol,
        context: SourceLookupContext,
        sourceJar: Path,
        expectedSha256: String?,
    ): SourceLookupResult {
        currentCoroutineContext().ensureActive()
        ensureOpen()
        if (!context.isCurrent()) return SourceLookupResult.Stale
        return try {
            val selectedSymbol = withBinaryIdentity(symbol)
            if (!context.isCurrent()) return SourceLookupResult.Stale
            val input = expectedSha256?.let { digest -> SourceArchiveInput.Verified(sourceJar, digest) }
                ?: SourceArchiveInput.Local(sourceJar)
            val storage = contentStore()
            val archive = storage.importArchive(input)
            val prefix = (selectedSymbol.origin as? SourceOrigin.Jdk)?.module?.let { module -> "$module/" }.orEmpty()
            if (storage.sourceFor(archive, selectedSymbol.binaryName, prefix) == null) {
                ensureOpen()
                return SourceLookupResult.Unavailable(MissingSourceReason.TYPE_NOT_PRESENT)
            }
            ensureOpen()
            if (!attachmentStore.record(selectedSymbol, context, sourceJar, archive, expectedSha256)) {
                ensureOpen()
                return if (context.isCurrent()) {
                    SourceLookupResult.Unavailable(MissingSourceReason.UNKNOWN_ORIGIN)
                } else {
                    SourceLookupResult.Stale
                }
            }
            locate(selectedSymbol, context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SourceStoreException) {
            failed(context, failure.failure)
        } catch (_: IOException) {
            failed(context, SourceFailure.IO_FAILURE)
        } catch (_: SecurityException) {
            failed(context, SourceFailure.IO_FAILURE)
        }
    }

    private suspend fun failed(context: SourceLookupContext, failure: SourceFailure): SourceLookupResult {
        ensureOpen()
        val current = context.isCurrent() && context.validateCurrent() && context.isCurrent()
        currentCoroutineContext().ensureActive()
        ensureOpen()
        return if (current) SourceLookupResult.Failed(failure) else SourceLookupResult.Stale
    }

    private fun ensureOpen() {
        if (closed.get()) throw CancellationException("Source navigation is closed")
    }

    private suspend fun contentStore(): SourceContentStore {
        synchronized(monitor) {
            ensureOpen()
            store?.let { return it }
        }
        return storeInitialization.withLock {
            runInterruptible(Dispatchers.IO) {
                synchronized(monitor) {
                    ensureOpen()
                    store?.let { return@runInterruptible it }
                }
                // Initialization may perform filesystem IO. Close never waits for it under the lifecycle monitor.
                val candidate = contentStoreFactory(contentRoot)
                var published = false
                try {
                    checkSourceInterruption()
                    synchronized(monitor) {
                        ensureOpen()
                        store = candidate
                        published = true
                        candidate
                    }
                } finally {
                    if (!published) candidate.close()
                }
            }
        }
    }

    private suspend fun withBinaryIdentity(symbol: ResolvedExternalSymbol): ResolvedExternalSymbol {
        val origin = symbol.origin as? SourceOrigin.Archive ?: return symbol
        if (origin.binarySha256 != null) {
            return symbol.copy(origin = origin.copy(binarySha256 = origin.binarySha256.lowercase()))
        }
        val digest = runInterruptible(Dispatchers.IO) {
            if (!Files.isRegularFile(origin.binaryJar)) throw SourceStoreException(SourceFailure.IO_FAILURE)
            Files.newInputStream(origin.binaryJar).use { copyAndDigest(it, MAX_BINARY_IDENTITY_BYTES) }
        }
        return symbol.copy(origin = origin.copy(binarySha256 = digest))
    }

    @Suppress("CyclomaticComplexMethod") // Cache and acquisition states have distinct cancellation and retry behavior.
    private suspend fun selectInput(
        symbol: ResolvedExternalSymbol,
        context: SourceLookupContext,
        revision: String,
        attempt: Long,
    ): InputResult {
        val identity = context.identity
        val policy = context.policy
        val key = InputKey(symbol.origin, identity.id, policy, revision)
        if (attempt == 0L) cachedInput(key)?.let { return it }
        return acquisitions.acquire(AcquisitionKey(key, attempt)) {
            if (attempt == 0L) cachedInput(key)?.let { return@acquire it }
            // The producer owns no requesting document or validation closure. Every borrower revalidates separately.
            val acquisitionContext = SourceLookupContext(identity, policy, isCurrent = { true })
            selectInputUnshared(symbol, acquisitionContext).also { result ->
                val lifetime = when (result) {
                    is InputResult.Available -> {
                        if (!result.cacheable || result.input is SourceArchiveInput.Local) null else Long.MAX_VALUE
                    }
                    is InputResult.Unavailable -> SOURCE_MISS_TTL_NANOS
                    is InputResult.Failed -> null
                }
                if (lifetime != null) {
                    synchronized(monitor) {
                        ensureOpen()
                        // An older missing-source request cannot erase a successful explicit retry.
                        if (result is InputResult.Available || inputs[key]?.value !is InputResult.Available) {
                            inputs[key] = CachedInput(result, System.nanoTime(), lifetime)
                            if (result is InputResult.Available) {
                                // Both policies can reuse existing verified bytes after an explicit download.
                                inputs[key.copy(policy = SourceLookupPolicy.LOCAL_ONLY)] = inputs.getValue(key)
                                inputs[key.copy(policy = SourceLookupPolicy.ALLOW_VERIFIED_DOWNLOADS)] =
                                    inputs.getValue(key)
                            }
                        }
                        while (inputs.size > MAX_SOURCE_INPUTS) inputs.remove(inputs.keys.first())
                    }
                }
            }
        }
    }

    private fun cachedInput(key: InputKey): InputResult? = synchronized(monitor) {
        inputs[key]?.takeIf { cached ->
            cached.lifetime == Long.MAX_VALUE || System.nanoTime() - cached.createdAt < cached.lifetime
        }?.value
    }

    private suspend fun selectInputUnshared(symbol: ResolvedExternalSymbol, context: SourceLookupContext): InputResult {
        attachmentStore.find(symbol, context)?.let { attachment ->
            val prefix = (symbol.origin as? SourceOrigin.Jdk)?.module?.let { module -> "$module/" }.orEmpty()
            return InputResult.Available(attachment, prefix, cacheable = false)
        }
        return when (val origin = symbol.origin) {
            is SourceOrigin.Bundled -> bundledSources.sourceInput(origin)?.let { input -> InputResult.Available(input) }
                ?: InputResult.Unavailable(MissingSourceReason.NO_ATTACHMENT)
            is SourceOrigin.Archive -> runInterruptible(Dispatchers.IO) {
                bundledSources.sourceInputForArchive(origin, symbol.binaryName)
            }?.let { input -> InputResult.Available(input) }
                ?: selectArchiveInput(origin, context)
            is SourceOrigin.Jdk -> {
                val sources = origin.imageHome.resolve("lib/src.zip")
                if (runInterruptible(Dispatchers.IO) { Files.isRegularFile(sources) }) {
                    InputResult.Available(SourceArchiveInput.Local(sources), origin.module + "/")
                } else {
                    InputResult.Unavailable(MissingSourceReason.NO_ATTACHMENT)
                }
            }
            is SourceOrigin.Unknown -> InputResult.Unavailable(MissingSourceReason.UNKNOWN_ORIGIN)
        }
    }

    private suspend fun selectArchiveInput(origin: SourceOrigin.Archive, context: SourceLookupContext): InputResult {
        val coordinates = origin.coordinates ?: return InputResult.Unavailable(MissingSourceReason.UNKNOWN_ORIGIN)
        val verified = sourceResolver as? VerifiedSourceArtifactResolver
            ?: return InputResult.Unavailable(MissingSourceReason.INTEGRITY_UNAVAILABLE)
        val binaryDigest = checkNotNull(origin.binarySha256) {
            "Source archive selection requires a captured binary identity"
        }
        val artifactRequest = VerifiedSourceArtifactRequest(
            coordinates = coordinates,
            selectedBinarySha256 = binaryDigest,
            allowDownload = context.policy == SourceLookupPolicy.ALLOW_VERIFIED_DOWNLOADS,
            contextId = context.identity.id,
        )
        return when (val resolved = verified.resolveVerifiedSourceArtifact(artifactRequest)) {
            is VerifiedSourceArtifactResult.Available -> InputResult.Available(
                SourceArchiveInput.Verified(resolved.path, resolved.expectedSha256),
            )
            VerifiedSourceArtifactResult.NotAvailable ->
                InputResult.Unavailable(MissingSourceReason.SOURCE_NOT_PUBLISHED)
            VerifiedSourceArtifactResult.IntegrityUnavailable ->
                InputResult.Unavailable(MissingSourceReason.INTEGRITY_UNAVAILABLE)
            VerifiedSourceArtifactResult.DownloadsDisabled ->
                InputResult.Unavailable(MissingSourceReason.DOWNLOADS_DISABLED)
            VerifiedSourceArtifactResult.UnsupportedArtifact ->
                InputResult.Unavailable(MissingSourceReason.UNSUPPORTED_LAYOUT)
            VerifiedSourceArtifactResult.BinaryMismatch ->
                InputResult.Unavailable(MissingSourceReason.BINARY_ORIGIN_MISMATCH)
            VerifiedSourceArtifactResult.LimitExceeded -> InputResult.Failed(SourceFailure.LIMIT_EXCEEDED)
            VerifiedSourceArtifactResult.Failed -> InputResult.Failed(SourceFailure.IO_FAILURE)
        }
    }

    private suspend fun locateInArchive(
        symbol: ResolvedExternalSymbol,
        context: SourceLookupContext,
        input: SourceArchiveInput,
        prefix: String,
    ): SourceLookupResult {
        val storage = contentStore()
        val archive = storage.importArchive(input)
        val source = storage.sourceFor(archive, symbol.binaryName, prefix)
            ?: return SourceLookupResult.Unavailable(MissingSourceReason.TYPE_NOT_PRESENT)
        val language = if (source.entryName.endsWith(".java")) "java" else "groovy"
        val key = DefinitionKey(language, source.contentDigest, symbol.binaryName, symbol.memberName)
        val definition = inspectShared(source, symbol, key)
        if (!context.isCurrent()) return SourceLookupResult.Stale
        val descriptor = SourceDocumentDescriptor(
            sourceDigest = archive.sha256,
            contentDigest = source.contentDigest,
            entryName = source.entryName,
            contentPath = source.contentPath,
            origin = symbol.origin,
            context = context.identity,
            displayName = displayName(symbol.origin),
            languageId = language,
        )
        ensureOpen()
        val uri = runInterruptible(Dispatchers.IO) { sourcePresentation.register(descriptor) }
        return definition?.let { SourceLookupResult.Located(uri, symbol.binaryName, it) }
            ?: SourceLookupResult.FileOnly(uri, symbol.binaryName, MissingSourceReason.DECLARATION_NOT_LOCATED)
    }

    private suspend fun inspectShared(
        source: MaterializedSource,
        symbol: ResolvedExternalSymbol,
        key: DefinitionKey,
    ): SourceDefinition? {
        synchronized(monitor) { definitions[key] }?.let { return it.value }
        return inspections.acquire(key) {
            synchronized(monitor) { definitions[key] }?.let { return@acquire it.value }
            val content = runInterruptible(Dispatchers.IO) {
                val bytes = ByteArrayOutputStream()
                val digest = Files.newInputStream(source.contentPath).use { input ->
                    copyAndDigest(input, MAX_INSPECTED_SOURCE_BYTES, bytes::write)
                }
                if (digest != source.contentDigest) throw SourceStoreException(SourceFailure.INTEGRITY_MISMATCH)
                // The presentation registry serves strict UTF-8 too: never publish a location for
                // replacement-decoded text that the managed source document cannot subsequently open.
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            }
            val producerContext = currentCoroutineContext()
            val definition = runInterruptible(Dispatchers.Default) {
                inspect(content, source, symbol, producerContext::ensureActive)
            }
            inspectionCount.incrementAndGet()
            synchronized(monitor) {
                ensureOpen()
                definitions[key] = CachedDefinition(definition)
                while (definitions.size > MAX_DECLARATION_INDEXES) definitions.remove(definitions.keys.first())
            }
            definition
        }
    }

    private fun inspect(
        content: String,
        source: MaterializedSource,
        symbol: ResolvedExternalSymbol,
        checkCancelled: () -> Unit,
    ): SourceDefinition? = if (source.entryName.endsWith(".java")) {
        if (symbol.memberName == null) {
            javaSourceInspector.inspectDefinitionFromContent(
                content,
                symbol.binaryName,
                source.entryName,
                checkCancelled,
            )
        } else {
            javaSourceInspector.inspectMemberDefinitionFromContent(
                content,
                symbol.binaryName,
                symbol.memberName,
                source.entryName,
                checkCancelled,
            )
        }
    } else if (symbol.memberName == null) {
        groovySourceInspector.inspect(content, symbol.binaryName, source.contentPath.toUri(), checkCancelled)
    } else {
        groovySourceInspector.inspectMember(
            content,
            symbol.binaryName,
            symbol.memberName,
            source.contentPath.toUri(),
            checkCancelled,
        )
    }

    private fun displayName(origin: SourceOrigin): String = when (origin) {
        is SourceOrigin.Archive -> origin.coordinates?.let { coordinates ->
            "${coordinates.group}:${coordinates.module}:${coordinates.version}"
        } ?: origin.binaryJar.fileName.toString()
        is SourceOrigin.Bundled -> origin.componentId
        is SourceOrigin.Jdk -> "JDK sources (${origin.module})"
        is SourceOrigin.Unknown -> "Dependency sources"
    }

    /** Legacy consumers are local-only; new definition requests must supply captured context to [locate]. */
    override suspend fun navigateToSource(classpathUri: URI, className: String): SourceNavigator.SourceResult =
        legacyLookup(classpathUri, className, null)

    override suspend fun navigateToMethodSource(
        classpathUri: URI,
        className: String,
        methodName: String,
    ): SourceNavigator.SourceResult = legacyLookup(classpathUri, className, methodName)

    private suspend fun legacyJdkLookup(
        resource: URI,
        binaryName: String,
        memberName: String?,
    ): SourceNavigator.SourceResult =
        when (val classResult = jdkSourceResolver.resolveJdkSource(resource, binaryName)) {
            is SourceNavigator.SourceResult.BinaryOnly -> classResult
            is SourceNavigator.SourceResult.SourceLocation -> {
                val methodInspection = memberName?.let { member ->
                    runCatching { Path.of(classResult.uri) }.getOrNull()?.let { sourcePath ->
                        javaSourceInspector.inspectMethod(sourcePath, binaryName, member)
                    }
                }
                when {
                    memberName == null -> classResult
                    methodInspection == null -> classResult.copy(
                        lineNumber = null,
                        documentation = null,
                        definition = null,
                    )
                    else -> classResult.copy(
                        lineNumber = methodInspection.lineNumber,
                        documentation = methodInspection.documentation,
                        definition = methodInspection.definition,
                    )
                }
            }
        }

    private suspend fun legacyLookup(
        resource: URI,
        binaryName: String,
        memberName: String?,
    ): SourceNavigator.SourceResult {
        if (resource.scheme == "jrt") return legacyJdkLookup(resource, binaryName, memberName)
        val origin = bundledSources.selectedOrigin(resource, binaryName)
            ?: parseJarClassResource(resource)?.let { SourceOrigin.Archive(it.archive) }
            ?: SourceOrigin.Unknown(resource)
        val context = SourceLookupContext(
            identity = SourceContextIdentity(sourceDigest(resource.toString())),
            isCurrent = { true },
        )
        return when (val result = locate(ResolvedExternalSymbol(binaryName, origin, memberName), context)) {
            is SourceLookupResult.Located -> SourceNavigator.SourceResult.SourceLocation(
                uri = result.uri,
                className = binaryName,
                lineNumber = result.definition.nameRange.startLine + 1,
                documentation = result.definition.documentation,
                definition = result.definition,
            )
            is SourceLookupResult.FileOnly -> SourceNavigator.SourceResult.SourceLocation(result.uri, binaryName)
            is SourceLookupResult.Unavailable ->
                SourceNavigator.SourceResult.BinaryOnly(resource, binaryName, result.reason.name)
            is SourceLookupResult.Failed ->
                SourceNavigator.SourceResult.BinaryOnly(resource, binaryName, result.failure.name)
            SourceLookupResult.Ambiguous ->
                SourceNavigator.SourceResult.BinaryOnly(resource, binaryName, "Ambiguous source origin")
            SourceLookupResult.Stale ->
                SourceNavigator.SourceResult.BinaryOnly(resource, binaryName, "Source context retired")
        }
    }

    fun getStatistics(): Map<String, Long> {
        val counts = synchronized(monitor) { store }?.workCounts() ?: SourceStoreWorkCounts(0, 0)
        return mapOf(
            "archiveImports" to counts.archiveImports,
            "archiveIndexes" to counts.archiveIndexes,
            "sourceMaterializations" to counts.sourceMaterializations,
            "declarationInspections" to inspectionCount.get(),
        )
    }

    override fun close() {
        val storage = synchronized(monitor) {
            if (!closed.compareAndSet(false, true)) return
            val owned = store
            store = null
            definitions.clear()
            inputs.clear()
            owned
        }
        shutdownResources(acquisitions::close, inspections::close, { storage?.close() })
    }

    private data class DefinitionKey(
        val language: String,
        val content: String,
        val binaryName: String,
        val memberName: String?,
    )
    private data class CachedDefinition(val value: SourceDefinition?)
    private data class CachedInput(val value: InputResult, val createdAt: Long, val lifetime: Long)
    private data class InputKey(
        val origin: SourceOrigin,
        val context: String,
        val policy: SourceLookupPolicy,
        val attachmentRevision: String,
    )
    private data class AcquisitionKey(val input: InputKey, val attempt: Long)
    private sealed interface InputResult {
        data class Available(val input: SourceArchiveInput, val prefix: String = "", val cacheable: Boolean = true) :
            InputResult
        data class Unavailable(val reason: MissingSourceReason) : InputResult
        data class Failed(val failure: SourceFailure) : InputResult
    }
}
