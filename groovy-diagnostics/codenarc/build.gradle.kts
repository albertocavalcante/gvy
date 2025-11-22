plugins {
    kotlin("jvm")
    groovy
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(project(":groovy-diagnostics:api"))
    implementation(project(":groovy-parser"))

    // LSP4J - Language Server Protocol implementation
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0")

    // CodeNarc
    implementation("org.codenarc:CodeNarc:3.6.0-groovy-4.0")
    implementation("org.gmetrics:GMetrics-Groovy4:2.1.0")
    implementation("org.apache.groovy:groovy:4.0.29")
    implementation("org.apache.groovy:groovy-json:4.0.29")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.slf4j:slf4j-api:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.0")
    testImplementation("io.mockk:mockk:1.14.6")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
