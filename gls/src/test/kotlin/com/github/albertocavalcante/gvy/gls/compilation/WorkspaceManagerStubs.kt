// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.compilation

import io.mockk.every

/**
 * For tests that mock [WorkspaceManager] through its legacy accessors: answer the per-document context
 * (#1235) from the stubbed [WorkspaceManager.getClasspathForFile], [WorkspaceManager.current] and
 * [WorkspaceManager.getConfigurationFingerprint], so existing expectations keep their meaning.
 */
internal fun WorkspaceManager.delegateCompileContextToLegacyStubs() {
    val manager = this
    every { manager.isExcludedSource(any<java.nio.file.Path>()) } returns false
    every { manager.compileContextFor(any(), any(), any()) } answers {
        val workspace = thirdArg<WorkspaceModel>()
        DocumentCompileContext(
            manager.getClasspathForFile(firstArg(), secondArg(), workspace),
            workspace.fingerprint,
            TargetSelection.LegacyOnly,
        )
    }
    every { manager.compileContextFor(any(), any()) } answers {
        manager.compileContextFor(firstArg(), secondArg(), manager.current())
    }
    every { manager.fingerprintFor(any(), any()) } answers { secondArg<WorkspaceModel>().fingerprint }
    every { manager.fingerprintFor(any()) } answers { manager.getConfigurationFingerprint() }
}
