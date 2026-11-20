// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build

/**
 * Structured, build-tool-neutral facts about a [ResolutionStatus.Failed].
 *
 * Variants hold only primitives, strings and lists of those, so a failure can cross a process boundary without
 * carrying Gradle, Maven or Tooling API types. Consumers match exhaustively on the variant.
 */
sealed interface ResolutionFailureDetail {

    /** Gradle could not provision the JDK a toolchain requires. */
    data class ToolchainProvisioning(
        val requiredVersion: Int?,
        val vendor: String? = null,
        val platform: String? = null,
        val suggestions: List<String> = emptyList(),
    ) : ResolutionFailureDetail

    /** The JDK running Gradle is not supported by the Gradle version in use. */
    data class GradleJdkIncompatible(
        val gradleVersion: String?,
        val jdkVersion: Int,
        val minGradleVersion: String?,
        val maxJdkVersion: String?,
        val suggestions: List<String> = emptyList(),
    ) : ResolutionFailureDetail

    /** Groovy's bundled ASM cannot read class files produced by the JDK in use. */
    data class GroovyJdkIncompatible(
        val majorVersion: Int,
        val jdkVersion: Int?,
        val currentJdk: Int,
        val suggestions: List<String> = emptyList(),
    ) : ResolutionFailureDetail
}

/**
 * Serializable summary of the exception behind a failure: its class name and message, never the live [Throwable].
 *
 * The producer that catches the exception logs it in full.
 */
data class ResolutionFailureCause(val type: String, val message: String?) {
    companion object {
        fun of(throwable: Throwable): ResolutionFailureCause =
            ResolutionFailureCause(throwable.javaClass.name, throwable.message)
    }
}
