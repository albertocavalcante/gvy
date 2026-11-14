// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.build.gradle

class GradleCompatibilityService {
    private val matrix = GradleRuntimeCompatibility { javaClass.getResourceAsStream(it) }

    /** Unknown pairs are distinct from known supported or unsupported pairs. */
    fun checkCompatibility(gradleVersion: String, jdkVersion: Int): GradleRuntimeSupport =
        matrix.check(gradleVersion, jdkVersion)

    /** Legacy preflight adapter: an unknown pair does not justify rejecting a build. */
    fun isCompatible(gradleVersion: String, jdkVersion: Int): Boolean =
        checkCompatibility(gradleVersion, jdkVersion) != GradleRuntimeSupport.UNSUPPORTED

    fun suggestFix(gradleVersion: String, jdkVersion: Int): String? {
        if (checkCompatibility(gradleVersion, jdkVersion) != GradleRuntimeSupport.UNSUPPORTED) return null
        val minRequired = matrix.minimum(jdkVersion)
        val maxExclusive = matrix.maximumExclusive(jdkVersion)
        val range = if (maxExclusive == null) {
            "Gradle $minRequired or newer"
        } else {
            "Gradle $minRequired up to, but excluding, $maxExclusive"
        }
        return "Gradle $gradleVersion is not compatible with JDK $jdkVersion. " +
            "Use $range or select a compatible Gradle JVM."
    }

    fun getMinimumGradleVersion(jdkVersion: Int): String? = matrix.minimum(jdkVersion)
}
