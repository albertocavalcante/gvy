plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "com.github.albertocavalcante"
version = rootProject.version

dependencies {
    // Groovy compiler (for native AST conversion)
    api(libs.groovy.core)

    // Logging
    implementation(libs.slf4j.api)

    // Detekt formatting
    detektPlugins(libs.detekt.formatting)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Code coverage
kover {
    reports {
        verify {
            rule {
                minBound(80) // Target 80% coverage for parser library
            }
        }
    }
}

// Publishing configuration for GitHub Packages
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "groovyparser-core"
            from(components["java"])

            pom {
                name.set("GroovyParser Core")
                description.set("A standalone Groovy parsing library with JavaParser-inspired API")
                url.set("https://github.com/albertocavalcante/groovy-lsp")

                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
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
                    connection.set("scm:git:git://github.com/albertocavalcante/groovy-lsp.git")
                    developerConnection.set("scm:git:ssh://github.com/albertocavalcante/groovy-lsp.git")
                    url.set("https://github.com/albertocavalcante/groovy-lsp")
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/albertocavalcante/groovy-lsp")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: project.findProperty("gpr.user") as String?
                password = System.getenv("GITHUB_TOKEN") ?: project.findProperty("gpr.key") as String?
            }
        }
    }
}
