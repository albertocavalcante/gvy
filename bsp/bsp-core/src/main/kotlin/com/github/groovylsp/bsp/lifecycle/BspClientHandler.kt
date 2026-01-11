package com.github.groovylsp.bsp.lifecycle

import ch.epfl.scala.bsp4j.BuildClient
import ch.epfl.scala.bsp4j.DidChangeBuildTarget
import ch.epfl.scala.bsp4j.LogMessageParams
import ch.epfl.scala.bsp4j.PublishDiagnosticsParams
import ch.epfl.scala.bsp4j.ShowMessageParams
import ch.epfl.scala.bsp4j.TaskFinishParams
import ch.epfl.scala.bsp4j.TaskProgressParams
import ch.epfl.scala.bsp4j.TaskStartParams
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Implements BuildClient callbacks for BSP communication.
 * Routes notifications from the build server to registered listeners.
 *
 * This handler follows the observer pattern, allowing multiple subscribers
 * to react to build server events.
 */
class BspClientHandler : BuildClient {
    private val logger = KotlinLogging.logger {}

    private val diagnosticListeners = CopyOnWriteArrayList<(PublishDiagnosticsParams) -> Unit>()
    private val showMessageListeners = CopyOnWriteArrayList<(ShowMessageParams) -> Unit>()
    private val logMessageListeners = CopyOnWriteArrayList<(LogMessageParams) -> Unit>()
    private val buildTargetChangeListeners = CopyOnWriteArrayList<(DidChangeBuildTarget) -> Unit>()
    private val taskStartListeners = CopyOnWriteArrayList<(TaskStartParams) -> Unit>()
    private val taskProgressListeners = CopyOnWriteArrayList<(TaskProgressParams) -> Unit>()
    private val taskFinishListeners = CopyOnWriteArrayList<(TaskFinishParams) -> Unit>()

    /**
     * Register a listener for diagnostics (errors, warnings, info).
     */
    fun onDiagnostics(handler: (PublishDiagnosticsParams) -> Unit) {
        diagnosticListeners.add(handler)
    }

    /**
     * Register a listener for show message notifications.
     */
    fun onShowMessage(handler: (ShowMessageParams) -> Unit) {
        showMessageListeners.add(handler)
    }

    /**
     * Register a listener for log message notifications.
     */
    fun onLogMessage(handler: (LogMessageParams) -> Unit) {
        logMessageListeners.add(handler)
    }

    /**
     * Register a listener for build target changes.
     */
    fun onBuildTargetChange(handler: (DidChangeBuildTarget) -> Unit) {
        buildTargetChangeListeners.add(handler)
    }

    /**
     * Register a listener for task start events.
     */
    fun onTaskStart(handler: (TaskStartParams) -> Unit) {
        taskStartListeners.add(handler)
    }

    /**
     * Register a listener for task progress events.
     */
    fun onTaskProgress(handler: (TaskProgressParams) -> Unit) {
        taskProgressListeners.add(handler)
    }

    /**
     * Register a listener for task finish events.
     */
    fun onTaskFinish(handler: (TaskFinishParams) -> Unit) {
        taskFinishListeners.add(handler)
    }

    /**
     * Clear all registered listeners.
     */
    fun clearListeners() {
        diagnosticListeners.clear()
        showMessageListeners.clear()
        logMessageListeners.clear()
        buildTargetChangeListeners.clear()
        taskStartListeners.clear()
        taskProgressListeners.clear()
        taskFinishListeners.clear()
    }

    // BuildClient interface implementations

    override fun onBuildShowMessage(params: ShowMessageParams) {
        logger.info { "[BSP Show] ${params.message}" }
        showMessageListeners.forEach { it(params) }
    }

    override fun onBuildLogMessage(params: LogMessageParams) {
        logger.debug { "[BSP Log] ${params.message}" }
        logMessageListeners.forEach { it(params) }
    }

    override fun onBuildPublishDiagnostics(params: PublishDiagnosticsParams) {
        val diagnosticCount = params.diagnostics?.size ?: 0
        logger.debug { "[BSP Diagnostics] ${params.textDocument.uri}: $diagnosticCount diagnostics" }
        diagnosticListeners.forEach { it(params) }
    }

    override fun onBuildTargetDidChange(params: DidChangeBuildTarget) {
        val changeCount = params.changes?.size ?: 0
        logger.info { "[BSP] Build targets changed: $changeCount targets affected" }
        buildTargetChangeListeners.forEach { it(params) }
    }

    override fun onBuildTaskStart(params: TaskStartParams) {
        val taskId = params.taskId.id
        val message = params.message ?: taskId
        logger.debug { "[BSP Task Start] $message" }
        taskStartListeners.forEach { it(params) }
    }

    override fun onBuildTaskProgress(params: TaskProgressParams) {
        val taskId = params.taskId.id
        val message = params.message ?: ""
        val progress = params.progress?.let { " ($it%)" } ?: ""
        logger.debug { "[BSP Task Progress] $taskId: $message$progress" }
        taskProgressListeners.forEach { it(params) }
    }

    override fun onBuildTaskFinish(params: TaskFinishParams) {
        val taskId = params.taskId.id
        val message = params.message ?: taskId
        val status = params.status?.name ?: "UNKNOWN"
        logger.debug { "[BSP Task Finish] $message ($status)" }
        taskFinishListeners.forEach { it(params) }
    }
}
