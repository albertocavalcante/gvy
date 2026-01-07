package com.github.groovylsp.bsp.compilation

import ch.epfl.scala.bsp4j.Diagnostic
import ch.epfl.scala.bsp4j.PublishDiagnosticsParams
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Observable diagnostic reporter following Bloop's streaming pattern.
 *
 * Allows multiple observers to subscribe to diagnostic updates and maintains
 * diagnostic history for late-joining subscribers (replay capability).
 *
 * Thread-safe for concurrent publishing and subscription.
 */
class DiagnosticReporter {

    private val logger = LoggerFactory.getLogger(DiagnosticReporter::class.java)

    // NOTE: CopyOnWriteArrayList for thread-safe iteration without locks
    //   during notification (optimized for read-heavy workload)
    private val observers = CopyOnWriteArrayList<(PublishDiagnosticsParams) -> Unit>()

    // Diagnostic history by file URI for replay
    private val history = ConcurrentHashMap<String, MutableList<Diagnostic>>()

    // Subscription ID generator
    private val nextSubscriptionId = AtomicLong(0)

    /**
     * Subscribes to diagnostic updates.
     *
     * The observer will receive all future diagnostics published via [report].
     * To receive historical diagnostics, call [replayTo] immediately after
     * subscribing.
     *
     * @param observer Callback invoked for each diagnostic publication
     * @return Subscription handle - close it to unsubscribe
     */
    fun subscribe(observer: (PublishDiagnosticsParams) -> Unit): Subscription {
        observers.add(observer)
        val id = nextSubscriptionId.getAndIncrement()
        logger.debug("Subscription #$id added (${observers.size} total)")

        return object : Subscription {
            private var closed = false

            override fun close() {
                if (!closed) {
                    observers.remove(observer)
                    closed = true
                    logger.debug("Subscription #$id closed (${observers.size} remaining)")
                }
            }
        }
    }

    /**
     * Reports diagnostics to all subscribers and updates history.
     *
     * Broadcasts the diagnostics to all registered observers and stores
     * them in the history map for replay to late subscribers.
     *
     * @param params Diagnostic parameters including file URI and diagnostics
     */
    fun report(params: PublishDiagnosticsParams) {
        // Update history
        val uri = params.textDocument.uri
        history[uri] = params.diagnostics.toMutableList()

        // Broadcast to observers
        // NOTE: CopyOnWriteArrayList allows safe iteration even if
        //   observers list is modified during notification
        observers.forEach { observer ->
            try {
                observer(params)
            } catch (e: Exception) {
                logger.error("Observer failed to handle diagnostics for $uri", e)
            }
        }

        logger.debug("Reported ${params.diagnostics.size} diagnostics for $uri")
    }

    /**
     * Clears diagnostics for a specific file.
     *
     * Reports empty diagnostics list to observers and removes from history.
     * Use when a file is fixed or deleted.
     *
     * @param uri File URI to clear
     */
    fun clearFile(uri: String) {
        history.remove(uri)

        // Report empty diagnostics to observers
        val textDoc = ch.epfl.scala.bsp4j.TextDocumentIdentifier(uri)
        val emptyParams =
            PublishDiagnosticsParams(textDoc, ch.epfl.scala.bsp4j.BuildTargetIdentifier(uri), emptyList(), true)
        report(emptyParams)

        logger.debug("Cleared diagnostics for $uri")
    }

    /**
     * Clears all diagnostics.
     *
     * Reports empty diagnostics for all tracked files and clears history.
     * Use for clean builds or when starting fresh compilation.
     */
    fun clearAll() {
        // Notify observers that all files are cleared
        history.keys.forEach { uri ->
            val textDoc = ch.epfl.scala.bsp4j.TextDocumentIdentifier(uri)
            val emptyParams =
                PublishDiagnosticsParams(textDoc, ch.epfl.scala.bsp4j.BuildTargetIdentifier(uri), emptyList(), true)
            observers.forEach { observer ->
                try {
                    observer(emptyParams)
                } catch (e: Exception) {
                    logger.error("Observer failed to handle clear for $uri", e)
                }
            }
        }

        val count = history.size
        history.clear()
        logger.info("Cleared all diagnostics ($count files)")
    }

    /**
     * Replays historical diagnostics to a specific observer.
     *
     * Sends all currently stored diagnostics to the observer.
     * Use immediately after subscribing to catch up late-joining clients.
     *
     * @param observer Observer to receive historical diagnostics
     */
    fun replayTo(observer: (PublishDiagnosticsParams) -> Unit) {
        val fileCount = history.size
        if (fileCount == 0) {
            logger.debug("No diagnostics to replay")
            return
        }

        // NOTE: Creating snapshot to avoid holding lock during callbacks
        val snapshot = history.entries.map { (uri, diagnostics) ->
            val textDoc = ch.epfl.scala.bsp4j.TextDocumentIdentifier(uri)
            PublishDiagnosticsParams(
                textDoc,
                ch.epfl.scala.bsp4j.BuildTargetIdentifier(uri),
                diagnostics.toList(),
                true,
            )
        }

        snapshot.forEach { params ->
            try {
                observer(params)
            } catch (e: Exception) {
                logger.error("Failed to replay diagnostics for ${params.textDocument.uri}", e)
            }
        }

        logger.debug("Replayed diagnostics for $fileCount files")
    }

    /**
     * Returns current number of subscribers (for monitoring/testing).
     */
    fun subscriberCount(): Int = observers.size

    /**
     * Returns current number of files with diagnostics (for monitoring/testing).
     */
    fun fileCount(): Int = history.size

    /**
     * Returns diagnostics for a specific file (for testing/debugging).
     *
     * @param uri File URI
     * @return List of diagnostics or null if no diagnostics for this file
     */
    fun getDiagnostics(uri: String): List<Diagnostic>? = history[uri]?.toList()

    /**
     * Subscription handle for diagnostic updates.
     *
     * Close to unsubscribe from future updates.
     */
    interface Subscription : Closeable {
        /**
         * Unsubscribes from diagnostic updates.
         *
         * After closing, the observer will no longer receive notifications.
         * Safe to call multiple times (idempotent).
         */
        override fun close()
    }
}
