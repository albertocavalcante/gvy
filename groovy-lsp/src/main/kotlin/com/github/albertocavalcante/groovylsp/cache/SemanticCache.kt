package com.github.albertocavalcante.groovylsp.cache

import com.github.albertocavalcante.groovycommon.hash.sha256
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.db.OccurrenceRole
import com.github.albertocavalcante.gvy.semantics.db.Range
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocument
import com.github.albertocavalcante.gvy.semantics.db.SymbolInfo
import com.github.albertocavalcante.gvy.semantics.db.SymbolKind
import com.github.albertocavalcante.gvy.semantics.db.SymbolOccurrence
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Persistent cache for SemanticDocuments to avoid recompilation.
 *
 * This cache stores compiled semantic information (symbols, occurrences) to disk,
 * keyed by file URI and source content hash. This enables:
 * - Fast workspace initialization (load from cache instead of recompile)
 * - Reduced compilation overhead for unchanged files
 * - Persistent semantic information across LSP restarts
 *
 * Thread-safe and designed for concurrent LSP operations.
 *
 * @param cacheDir Directory to store cache files
 */
class SemanticCache(private val cacheDir: Path) {
    private val logger = KotlinLogging.logger {}
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }
    private val uriHashCache = ConcurrentHashMap<String, String>()

    init {
        // Ensure cache directory exists
        if (!cacheDir.exists()) {
            cacheDir.toFile().mkdirs()
        }
    }

    /**
     * Save SemanticDocument to disk cache.
     *
     * @param uri The file URI
     * @param document The semantic document to cache
     * @param sourceHash Hash of the source content for validation
     */
    suspend fun save(uri: URI, document: SemanticDocument, sourceHash: String) = withContext(Dispatchers.IO) {
        try {
            val cacheFile = getCacheFile(uri)
            val cached = CachedSemanticDocument(
                uri = uri.toString(),
                sourceHash = sourceHash,
                symbols = document.symbols.map { it.toSerialized() },
                occurrences = document.occurrences.map { it.toSerialized() },
            )

            val jsonString = json.encodeToString(cached)
            cacheFile.writeText(jsonString)

            logger.debug { "Saved semantic cache for: $uri" }
        } catch (e: Exception) {
            logger.warn { "Failed to save semantic cache for $uri: ${e.message}" }
        }
    }

    /**
     * Load SemanticDocument from disk cache if valid.
     *
     * Returns null if:
     * - Cache file does not exist
     * - Source hash does not match (file changed)
     * - Cache is corrupted or cannot be read
     *
     * @param uri The file URI
     * @param sourceHash Hash of current source content
     * @return The cached SemanticDocument or null if invalid
     */
    suspend fun load(uri: URI, sourceHash: String): SemanticDocument? = withContext(Dispatchers.IO) {
        try {
            val cacheFile = getCacheFile(uri)
            if (!cacheFile.exists()) {
                logger.trace { "No cache file for: $uri" }
                return@withContext null
            }

            val jsonString = cacheFile.readText()
            val cached = json.decodeFromString<CachedSemanticDocument>(jsonString)

            // Validate source hash
            if (cached.sourceHash != sourceHash) {
                logger.debug { "Cache invalid for $uri (hash mismatch)" }
                return@withContext null
            }

            logger.debug { "Loaded semantic cache for: $uri" }
            SemanticDocument(
                uri = URI.create(cached.uri),
                symbols = cached.symbols.map { it.toSymbolInfo() },
                occurrences = cached.occurrences.map { it.toSymbolOccurrence() },
            )
        } catch (e: Exception) {
            logger.debug { "Failed to load semantic cache for $uri: ${e.message}" }
            null
        }
    }

    /**
     * Check if cache is valid for a file.
     *
     * @param uri The file URI
     * @param sourceHash Hash of current source content
     * @return true if cache exists and hash matches
     */
    suspend fun isValid(uri: URI, sourceHash: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val cacheFile = getCacheFile(uri)
            if (!cacheFile.exists()) {
                return@withContext false
            }

            val jsonString = cacheFile.readText()
            val cached = json.decodeFromString<CachedSemanticDocument>(jsonString)
            cached.sourceHash == sourceHash
        } catch (e: Exception) {
            logger.debug { "Cache validation failed for $uri: ${e.message}" }
            false
        }
    }

    /**
     * Invalidate (delete) cache for a file.
     *
     * @param uri The file URI
     */
    suspend fun invalidate(uri: URI) = withContext(Dispatchers.IO) {
        try {
            val cacheFile = getCacheFile(uri)
            if (cacheFile.deleteIfExists()) {
                uriHashCache.remove(uri.toString())
                logger.debug { "Invalidated cache for: $uri" }
            }
        } catch (e: Exception) {
            logger.warn { "Failed to invalidate cache for $uri: ${e.message}" }
        }
    }

    /**
     * Clear all cache files.
     */
    suspend fun clear() = withContext(Dispatchers.IO) {
        try {
            cacheDir.toFile().listFiles()?.forEach { file ->
                if (file.extension == "json") {
                    file.delete()
                }
            }
            logger.info { "Cleared all semantic cache" }
        } catch (e: Exception) {
            logger.warn { "Failed to clear cache: ${e.message}" }
        }
    }

    /**
     * Get cache file path for a URI.
     */
    private fun getCacheFile(uri: URI): Path {
        // Create a safe filename from URI, using cached hash
        val uriString = uri.toString()
        val hash = uriHashCache.getOrPut(uriString) { sha256(uriString) }
        return cacheDir.resolve("$hash.json")
    }

    companion object {
        /**
         * Hash source content to detect changes.
         */
        fun hashSource(content: String): String = sha256(content)
    }
}

/**
 * Serializable representation of a SemanticDocument.
 */
@Serializable
private data class CachedSemanticDocument(
    val uri: String,
    val sourceHash: String,
    val symbols: List<SerializedSymbolInfo>,
    val occurrences: List<SerializedOccurrence>,
)

/**
 * Serializable representation of SymbolInfo.
 */
@Serializable
private data class SerializedSymbolInfo(
    val symbol: String,
    val kind: String,
    val range: SerializedRange,
    val name: String,
    val owner: String?,
    val type: SerializedType? = null,
)

/**
 * Serializable representation of SymbolOccurrence.
 */
@Serializable
private data class SerializedOccurrence(val symbol: String, val range: SerializedRange, val role: String)

/**
 * Serializable representation of Range.
 */
@Serializable
private data class SerializedRange(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int)

/**
 * Serializable representation of SemanticType.
 * We use a simple string representation for caching.
 */
@Serializable
private data class SerializedType(val representation: String)

// Extension functions for conversion

private fun SymbolInfo.toSerialized() = SerializedSymbolInfo(
    symbol = symbol,
    kind = kind.name,
    range = range.toSerialized(),
    name = name,
    owner = owner,
    type = type?.toSerialized(),
)

private fun SymbolOccurrence.toSerialized() = SerializedOccurrence(
    symbol = symbol,
    range = range.toSerialized(),
    role = role.name,
)

private fun Range.toSerialized() = SerializedRange(
    startLine = startLine,
    startColumn = startColumn,
    endLine = endLine,
    endColumn = endColumn,
)

private fun SemanticType.toSerialized() = SerializedType(
    representation = toString(),
)

private fun SerializedSymbolInfo.toSymbolInfo() = SymbolInfo(
    symbol = symbol,
    kind = SymbolKind.valueOf(kind),
    range = range.toRange(),
    name = name,
    owner = owner,
    type = type?.toSemanticType(),
)

private fun SerializedOccurrence.toSymbolOccurrence() = SymbolOccurrence(
    symbol = symbol,
    range = range.toRange(),
    role = OccurrenceRole.valueOf(role),
)

private fun SerializedRange.toRange() = Range(
    startLine = startLine,
    startColumn = startColumn,
    endLine = endLine,
    endColumn = endColumn,
)

@Suppress("FunctionOnlyReturningConstant") // TODO: Implement full type deserialization when needed for cache validation
private fun SerializedType.toSemanticType(): SemanticType? {
    // For now, we don't fully deserialize types - just return null
    // Full type deserialization would require parsing the string representation
    // This is acceptable as types are primarily used for display, not cache validation
    return null
}
