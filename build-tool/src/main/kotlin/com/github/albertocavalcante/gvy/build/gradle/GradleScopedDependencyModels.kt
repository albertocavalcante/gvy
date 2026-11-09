package com.github.albertocavalcante.gvy.build.gradle

/** Getter-shaped transport only; nullable fields are validated before becoming evidence. */
internal interface GradleScopedDependencyModel {
    val buildDirectory: String?
    val targets: List<GradleSourceSetModel?>?
}

internal interface GradleSourceSetModel {
    val projectPath: String?
    val projectDirectory: String?
    val sourceSet: String?
    val configuration: String?

    /** Nominal configuration queried for exact-file coordinate enrichment, not effective graph edges. */
    val enrichmentConfiguration: String?
    val state: String?
    val sourceRoots: List<String?>?
    val artifacts: List<GradleClasspathEntryModel?>?
}

internal interface GradleClasspathEntryModel {
    val path: String?
    val coordinateCandidates: List<GradleModuleCoordinatesModel?>?
}

internal interface GradleModuleCoordinatesModel {
    val group: String?
    val module: String?
    val version: String?
}
