plugins {
    kotlin("jvm")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0")
}
