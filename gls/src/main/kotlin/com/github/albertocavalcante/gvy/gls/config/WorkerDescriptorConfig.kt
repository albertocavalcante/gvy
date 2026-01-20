package com.github.albertocavalcante.gvy.gls.config

data class WorkerDescriptorConfig(
    val id: String,
    val minVersion: String,
    val maxVersion: String? = null,
    val features: Set<String> = emptySet(),
    val connector: String = "in-process",
)
