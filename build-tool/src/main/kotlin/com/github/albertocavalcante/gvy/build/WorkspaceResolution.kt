package com.github.albertocavalcante.gvy.build

import java.nio.file.Path

data class WorkspaceResolution(
    val dependencies: List<Path>,
    val sourceDirectories: List<Path>,
    val status: ResolutionStatus = ResolutionStatus.Success,
) {
    val isUsable: Boolean
        get() = status.isUsable

    companion object {
        fun empty(): WorkspaceResolution = WorkspaceResolution(emptyList(), emptyList())

        fun failed(code: String, message: String, cause: Throwable? = null): WorkspaceResolution =
            WorkspaceResolution(emptyList(), emptyList(), ResolutionStatus.Failed(code, message, cause))
    }
}
