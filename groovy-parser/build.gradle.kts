plugins {
    kotlin("jvm")
}

group = "com.github.albertocavalcante"
version = rootProject.version

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    api(libs.groovy.core)

    implementation(libs.kotlin.collections.immutable)
    implementation(libs.slf4j.api)

    detektPlugins(libs.detekt.formatting)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.jqwik)
}

tasks.test {
    useJUnitPlatform()
}

kover {
    reports {
        verify {
            rule {
                minBound(54) // Minimum line coverage: 54% for parser module
            }
        }
    }
}
