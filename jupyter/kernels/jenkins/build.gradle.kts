plugins {
    kotlin("jvm")
    alias(libs.plugins.shadow)
    application
}

dependencies {
    // Core Kernel Logic
    implementation(project(":jupyter:kernel-core"))

    // TODO: Add Jenkins Pipeline dependencies (Groovy 2.4) when we verify compatibility
    // For now we just compile against core.
    // implementation("com.lesfurets:jenkins-pipeline-unit:1.3")
    // implementation("org.codehaus.groovy:groovy-all:2.4.21")

    // Logging
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    // Detekt formatting
    detektPlugins(libs.detekt.formatting)
}

application {
    mainClass = "com.github.albertocavalcante.groovyjupyter.jenkins.MainKt"
}

tasks.shadowJar {
    archiveBaseName = "jenkins-kernel"
    archiveClassifier = "all"

    manifest {
        attributes["Main-Class"] = "com.github.albertocavalcante.groovyjupyter.jenkins.MainKt"
    }
}
