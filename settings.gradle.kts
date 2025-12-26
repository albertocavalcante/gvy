// TODO: Consider migrating from gradle-pre-commit-git-hooks to lefthook for:
// - Faster hook execution (no Gradle daemon startup)
// - Better cross-platform support
// - Unified config with lefthook.yml (currently has separate YAML/Python hooks)
plugins {
    id("org.danilopianini.gradle-pre-commit-git-hooks") version "2.1.6"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
        // Gradle repository for Tooling API
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
    }
}

gitHooks {
    preCommit {
        from(file("tools/hooks/pre-commit"))
    }

    commitMsg {
        conventionalCommits {
            // Supports: feat, fix, build, chore, ci, docs, perf, refactor, revert, style, test
            defaultTypes()
        }
    }

    createHooks(true) // Allow overwriting existing hooks
}

rootProject.name = "groovy-lsp-root"

include("groovy-formatter")
include("groovy-parser")
include("groovy-common")
include("groovy-lsp")
include("tests")
include("groovy-diagnostics:api")
include("groovy-diagnostics:codenarc")
include("groovy-jenkins")
include("groovy-gdsl")
include("groovy-build-tool")
include("groovy-spock")
include("groovy-testing")
include("groovy-junit")
include("jupyter:kernel-core")
include("jupyter:kernels:groovy")
include("jupyter:kernels:jenkins")
include("groovy-repl")
include("tools:jenkins-extractor")
