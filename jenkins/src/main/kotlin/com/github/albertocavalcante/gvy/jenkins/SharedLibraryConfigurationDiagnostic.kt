// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jenkins

internal data class SharedLibraryConfigurationParseResult(
    val libraries: List<SharedLibrary>,
    val diagnostics: List<SharedLibraryConfigurationDiagnostic>,
)

/** A settings problem containing no library names, source values or exception details. */
internal data class SharedLibraryConfigurationDiagnostic(val entryIndex: Int, val code: Code) {
    val settingPath: String
        get() = "jenkins.sharedLibraries[$entryIndex]"

    val message: String
        get() = code.message

    enum class Code(val message: String) {
        TYPED_SOURCE_DISABLED("Source-based library declarations are not supported; entry is inactive."),
        CONFLICTING_FORMATS("A source declaration cannot be combined with jar or sourcesJar; entry is inactive."),
        UNREADABLE_ENTRY("Library configuration entry could not be read; entry is inactive."),
    }
}
