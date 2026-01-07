package com.github.groovylsp.bsp.compilation

import ch.epfl.scala.bsp4j.PublishDiagnosticsParams
import ch.epfl.scala.bsp4j.StatusCode
import ch.epfl.scala.bsp4j.TaskId

/**
 * Events emitted during compilation for streaming to observers.
 *
 * Follows Bloop's pattern of observable compilation events that can be
 * subscribed to by multiple clients for progress tracking and diagnostics.
 */
sealed class CompilationEvent {

    /**
     * Compilation task has started.
     *
     * @property taskId Unique identifier for this compilation task
     * @property message Optional descriptive message (e.g., "Compiling 3 targets")
     */
    data class Started(val taskId: TaskId, val message: String? = null) : CompilationEvent()

    /**
     * Compilation progress update.
     *
     * @property taskId Task identifier
     * @property progress Current progress value (e.g., files compiled)
     * @property total Total expected value (e.g., total files)
     */
    data class Progress(val taskId: TaskId, val progress: Long, val total: Long) : CompilationEvent() {
        /**
         * Progress as a percentage (0-100).
         */
        val percentage: Int
            get() = if (total > 0) ((progress * 100) / total).toInt() else 0
    }

    /**
     * Diagnostic information for a source file.
     *
     * @property params BSP diagnostic parameters including file URI and diagnostics
     */
    data class Diagnostic(val params: PublishDiagnosticsParams) : CompilationEvent()

    /**
     * Compilation task has finished.
     *
     * @property taskId Task identifier
     * @property statusCode Final status (OK, ERROR, CANCELLED)
     */
    data class Finished(val taskId: TaskId, val statusCode: StatusCode) : CompilationEvent()
}
