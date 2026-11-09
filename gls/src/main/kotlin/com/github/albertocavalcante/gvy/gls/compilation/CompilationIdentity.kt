package com.github.albertocavalcante.gvy.gls.compilation

import com.github.albertocavalcante.gvy.gls.services.DocumentSnapshot
import kotlinx.coroutines.Deferred

/** Inputs that must agree before callers can share a pending compilation. */
internal class CompilationIdentity(
    val content: String,
    val configurationFingerprint: String,
    val document: DocumentSnapshot?,
) {
    fun matches(other: CompilationIdentity): Boolean = content == other.content &&
        configurationFingerprint == other.configurationFingerprint && document === other.document
}

/** A pending job retains its immutable inputs, including an open buffer's reference identity. */
internal class PendingCompilation(val identity: CompilationIdentity, val deferred: Deferred<CompilationResult>) {
    // A registered LAZY coroutine is shareable before it becomes active.
    val isPending: Boolean get() = !deferred.isCompleted && !deferred.isCancelled
}
