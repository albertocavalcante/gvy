plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    `maven-publish`
}

description = "Maven BSP (Build Server Protocol) server implementation"

dependencies {
    // BSP Core infrastructure
    api(project(":bsp:bsp-core"))

    // BSP Protocol
    api(libs.bsp4j)
    api(libs.lsp4j.jsonrpc)

    // Maven Embedder (POM parsing, model building)
    implementation(libs.maven.embedder)
    implementation(libs.maven.compat)

    // Maven Resolver (Aether) for dependency resolution
    implementation(libs.maven.resolver.connector.basic)
    implementation(libs.maven.resolver.transport.http)
    implementation(libs.maven.resolver.supplier)

    // Coroutines
    implementation(libs.kotlin.coroutines.core)

    // Serialization (for connection JSON)
    implementation(libs.kotlin.serialization.json)

    // Logging
    implementation(libs.kotlin.logging)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.logback.classic)
}

application {
    mainClass.set("com.github.groovylsp.bsp.maven.launcher.MavenBspLauncher")
}

tasks.test {
    useJUnitPlatform()
}

// Create fat JAR for standalone distribution
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath
            .get()
            .map { if (it.isDirectory) it else zipTree(it) }
    })

    manifest {
        attributes["Main-Class"] = "com.github.groovylsp.bsp.maven.launcher.MavenBspLauncher"
    }
}

// Maven Central publishing configuration
publishing {
    val repoPath = "albertocavalcante/gvy"
    val repoUrl = "https://github.com/$repoPath"

    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("Maven BSP Server")
                description.set(project.description)
                url.set(repoUrl)

                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }

                developers {
                    developer {
                        id.set("albertocavalcante")
                        name.set("Alberto Cavalcante")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/$repoPath.git")
                    developerConnection.set("scm:git:ssh://github.com/$repoPath.git")
                    url.set(repoUrl)
                }
            }
        }
    }
}
