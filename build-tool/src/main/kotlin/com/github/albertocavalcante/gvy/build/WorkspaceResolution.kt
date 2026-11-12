// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

import java.nio.file.Path

/**
 * The existing flat classpath and source-root result, with optional producer-owned target evidence.
 *
 * Existing adapters keep their source constructor/copy/destructuring behavior and default to
 * [TargetDependencyEvidence.Legacy]. Flat success or empty lists never establish target absence.
 * Scoped evidence does not alter [status], [isUsable], or either flat list; producers and consumers
 * migrate explicitly in later changes. This additive source contract does not promise JVM binary
 * ABI compatibility for callers compiled against the previous data-class constructor or copy.
 */
data class WorkspaceResolution(
    val dependencies: List<Path>,
    val sourceDirectories: List<Path>,
    val status: ResolutionStatus = ResolutionStatus.Success,
    val targetDependencies: TargetDependencyEvidence = TargetDependencyEvidence.Legacy,
) {
    val isUsable: Boolean
        get() = status.isUsable

    companion object {
        fun empty(): WorkspaceResolution = WorkspaceResolution(emptyList(), emptyList())

        fun failed(code: String, message: String, cause: Throwable? = null): WorkspaceResolution =
            WorkspaceResolution(emptyList(), emptyList(), ResolutionStatus.Failed(code, message, cause))
    }
}
