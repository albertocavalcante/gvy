package com.github.albertocavalcante.gvy.build

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Path

/** Coalesces events from one watch loop; the caller owns the scope and its cancellation. */
internal class BuildFileChangeDebouncer(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val delayMs: Long,
    private val onChange: (Path, String) -> Unit,
) {
    private var pending: Job? = null

    fun submit(projectDir: Path, filename: String) {
        pending?.cancel()
        pending = scope.launch(dispatcher) {
            if (delayMs > 0) delay(delayMs)
            onChange(projectDir, filename)
        }
    }
}
