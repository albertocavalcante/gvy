plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `maven-publish`
}

description = "Reusable BSP (Build Server Protocol) infrastructure for JVM language servers"

dependencies {
    // BSP Protocol
    api(libs.bsp4j)
    api(libs.lsp4j.jsonrpc)

    // Functional Programming - Arrow-kt core types (Either, Option, etc.)
    api(libs.arrow.core)

    // Coroutines
    api(libs.kotlin.coroutines.core)

    // Serialization (for connection details parsing)
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

tasks.test {
    useJUnitPlatform()
}

// Maven Central publishing configuration
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("BSP Core")
                description.set(project.description)
                url.set("https://github.com/GroovyLanguageServer/groovy-language-server")

                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }

                developers {
                    developer {
                        id.set("groovylsp")
                        name.set("Groovy Language Server Contributors")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/GroovyLanguageServer/groovy-language-server.git")
                    developerConnection.set("scm:git:ssh://github.com/GroovyLanguageServer/groovy-language-server.git")
                    url.set("https://github.com/GroovyLanguageServer/groovy-language-server")
                }
            }
        }
    }
}
