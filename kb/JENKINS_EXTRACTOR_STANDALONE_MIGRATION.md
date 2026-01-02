# Jenkins Extractor Standalone Migration Plan

This document outlines the strategy and implementation for extracting the `jenkins-extractor` tool from the `groovy-lsp`
monorepo into a standalone, read-only mirror repository.

## 1. Objective

Extract `tools/jenkins-extractor` and its internal dependencies (`groovy-jenkins`, `groovy-gdsl`, etc.) into a dedicated
repository: `albertocavalcante/jenkins-pipeline-metadata-extractor`.

The target repository will be managed by a bot (**gls-bot**) and will be kept in sync automatically whenever changes
occur in the main codebase.

## 2. Bot Identity

- **Name:** `gls-bot`
- **Email:** `gls-bot@cavalcante.uk`
- **Role:** Committer for the mirror repository (preserving original authorship metadata).

## 3. Implementation Components

### A. Mirror Overrides (`tools/jenkins-extractor/mirror/`)

To allow the extractor to run standalone without the root monorepo's Gradle configuration, we created "shim" files that
are swapped into the root of the destination repo during migration:

- `settings.gradle.kts`: Configures the project to look for dependencies in a local `deps/` folder.
- `build.gradle.kts`: A streamlined build script for the standalone tool.
- `README.md`: Tool-specific documentation for the new repository.

### B. Copybara Configuration (`infra/copybara/jenkins-extractor.bara.sky`)

We use **Google Copybara** to handle the transformation and movement of code:

- **Root Shift:** Moves `tools/jenkins-extractor/*` to the root `/`.
- **Dependency Bundling:** Moves required libraries (`groovy-jenkins`, `groovy-gdsl`, `groovy-common`,
  `groovy-build-tool`) into a `deps/` directory.
- **Reference Fixing:** Automatically rewrites Gradle `project(":module")` references to `project(":deps:module")`.
- **Author Management:** Forwards original commit authors while using the bot as the committer.

### C. Automation (`.github/workflows/mirror-jenkins-extractor.yml`)

A GitHub Action that triggers on any push to `main` affecting the extractor or its dependencies:

- **Security:** All actions are pinned to specific SHA hashes.
- **Tooling:** Uses `Olivr/copybara-action@v1.2.5`.
- **Authentication:** Utilizes `GLS_BOT_TOKEN` secret.

## 4. Migration Mapping

| Source Path                                       | Destination Path          |
| ------------------------------------------------- | ------------------------- |
| `tools/jenkins-extractor/**`                      | `/` (Root)                |
| `tools/jenkins-extractor/mirror/build.gradle.kts` | `/build.gradle.kts`       |
| `groovy-jenkins/**`                               | `deps/groovy-jenkins/`    |
| `groovy-gdsl/**`                                  | `deps/groovy-gdsl/`       |
| `groovy-common/**`                                | `deps/groovy-common/`     |
| `groovy-build-tool/**`                            | `deps/groovy-build-tool/` |

## 5. Setup Requirements

To activate the migration, the following manual steps are required:

1. **Create Repo:** Create `albertocavalcante/jenkins-pipeline-metadata-extractor` on GitHub.
2. **Setup Secret:** Generate a Personal Access Token (PAT) with `repo` scope for the bot and add it as `GLS_BOT_TOKEN`
   in the `groovy-lsp` repository secrets.
3. **Branch Protection:** (Optional) Set the destination `main` branch to read-only for everyone except the bot.

## 6. Future Evolution

Once the core libraries are published to a Maven repository (e.g., GitHub Packages), the Copybara configuration can be
simplified to remove the `deps/` bundling and instead pull dependencies from the remote registry.
