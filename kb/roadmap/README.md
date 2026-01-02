# Groovy Devtools Roadmap (Internal)

This is a working internal roadmap. The user-facing summary lives in `../../docs/roadmap.md`.

## Status snapshot

| Module                             | Maturity | Description                                          |
| ---------------------------------- | -------- | ---------------------------------------------------- |
| Core LSP (`groovy-lsp`)            | WIP      | Text sync, completion, hover, navigation, formatting |
| Parser (`parser/*`)                | Stable   | AST parsing with error recovery                      |
| Diagnostics (`groovy-diagnostics`) | Beta     | Compiler + CodeNarc diagnostics                      |
| Jenkins (`groovy-jenkins`)         | Beta     | Pipeline metadata, completions, diagnostics          |
| Spock (`groovy-spock`)             | Beta     | Spec detection, block awareness                      |
| GDSL (`groovy-gdsl`)               | Alpha    | Script execution foundation                          |
| Build tool (`groovy-build-tool`)   | Alpha    | BSP and Gradle exploration                           |
| Formatter (`groovy-formatter`)     | Alpha    | OpenRewrite formatting                               |

## Current themes

- Core LSP stability and performance: `../OPTIMIZATION_PLAN.md`, `../PHASE_3_PERFORMANCE_PLAN.md`
- Jenkins metadata + GDSL execution: `../JENKINS_INTELLISENSE_ARCHITECTURE.md`, `../specs/GDSL_EXECUTION_ENGINE.md`
- Jenkins versioned metadata and overrides: `../specs/VERSIONED_METADATA.md`, `../specs/USER_OVERRIDES.md`
- Spock support expansion: `../SPOCK_SUPPORT.md`, `../SPOCK_AST_SUPPORT.md`
- Build tool integration research: `../GRADLE_BUILD_SERVER_INTEGRATION.md`

## Drafted specifications

- GDSL Execution Engine: `../specs/GDSL_EXECUTION_ENGINE.md`
- Versioned Jenkins Metadata: `../specs/VERSIONED_METADATA.md`
- User Override System: `../specs/USER_OVERRIDES.md`

## Backlog (not drafted yet)

- Type hierarchy, call hierarchy, inlay hints, inline values
- Workspace file operations (create/rename/delete) and configuration refresh
- Notebook document synchronization
- Gradle DSL and build script intelligence
