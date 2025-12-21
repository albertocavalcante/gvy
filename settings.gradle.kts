plugins {
    id("org.danilopianini.gradle-pre-commit-git-hooks") version "2.1.5"
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
        from {
            """
            #!/bin/sh
            # Cross-platform: sh works on all systems via Git

            # Check not on main branch (works on Windows/Mac/Linux)
            branch=${'$'}(git rev-parse --abbrev-ref HEAD)
            if [ "${'$'}branch" = "main" ]; then
                echo "ERROR: Direct commits to main branch are forbidden!"
                echo "Please create a feature branch: git checkout -b your-branch-name"
                exit 1
            fi

            echo "Running auto-formatting and code quality fixes..."

            # Store list of staged files
            staged_files=${'$'}(git diff --cached --name-only --diff-filter=d)

            # Run auto-fixers (spotlessApply + detektAutoCorrect)
            ./gradlew lintFix --quiet || exit 1

            # Re-stage modified files
            for file in ${'$'}staged_files; do
                if [ -f "${'$'}file" ]; then
                    git add "${'$'}file"
                fi
            done

            echo "✓ Code formatting applied and files re-staged"
            """
        }
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
include("jupyter:kernel-core")
include("jupyter:kernels:groovy")
include("jupyter:kernels:jenkins")
include("groovy-repl")
include("tools:jenkins-extractor")
