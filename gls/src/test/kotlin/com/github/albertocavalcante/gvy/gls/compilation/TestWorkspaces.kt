// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import java.nio.file.Path

/** A fixed workspace model for orchestrator tests that stub [WorkspaceManager]. */
internal fun testWorkspace(
    fingerprint: String,
    classpath: List<Path> = emptyList(),
    sourceRoots: List<Path> = emptyList(),
    sources: List<Path> = emptyList(),
) = WorkspaceModel(null, classpath, sourceRoots, sources) { _, _ -> fingerprint }
