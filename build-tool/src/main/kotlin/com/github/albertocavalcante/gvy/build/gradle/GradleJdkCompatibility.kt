// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.gradle

/** Compatibility facade retained for existing callers. */
object GradleJdkCompatibility {
    private val service = GradleCompatibilityService()

    fun checkCompatibility(gradleVersion: String, jdkMajorVersion: Int): GradleRuntimeSupport =
        service.checkCompatibility(gradleVersion, jdkMajorVersion)

    /** Returns true only when the published matrix supports the pair. */
    fun isSupported(gradleVersion: String, jdkMajorVersion: Int): Boolean =
        checkCompatibility(gradleVersion, jdkMajorVersion) == GradleRuntimeSupport.SUPPORTED

    fun getMinimumGradleVersion(jdkMajorVersion: Int): String? = service.getMinimumGradleVersion(jdkMajorVersion)

    fun getCurrentJdkMajorVersion(): Int = Runtime.version().feature()

    fun suggestFix(gradleVersion: String, jdkMajorVersion: Int): String =
        service.suggestFix(gradleVersion, jdkMajorVersion)
            ?: "Gradle $gradleVersion may not be compatible with JDK $jdkMajorVersion."
}
