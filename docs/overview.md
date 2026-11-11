# Repository overview

Use this map to find the component responsible for a change. For installation and editor features, start with the
[project README](../README.md); for development setup, see [Contributing](../CONTRIBUTING.md).

## Language server and analysis

| Path                                                | Responsibility                                                       |
| --------------------------------------------------- | -------------------------------------------------------------------- |
| [gls/](../gls/)                                     | Kotlin/JVM language server and command-line entry point              |
| [parser/](../parser/)                               | Parser interfaces and native, core, and OpenRewrite implementations  |
| [semantics/](../semantics/)                         | Semantic analysis and type inference ([design](semantics/design.md)) |
| [diagnostics/](../diagnostics/)                     | Compiler and CodeNarc diagnostics, with SARIF output support         |
| [fmt/](../fmt/)                                     | Groovy formatting using OpenRewrite                                  |
| [indexer/](../indexer/)                             | Symbol indexing and SCIP/LSIF integrations                           |
| [common/](../common/) and [markdown/](../markdown/) | Shared utilities and Markdown rendering                              |

## Frameworks and build integration

| Path                                                | Responsibility                                         |
| --------------------------------------------------- | ------------------------------------------------------ |
| [jenkins/](../jenkins/)                             | Jenkins pipeline metadata, completion, and diagnostics |
| [dsl/](../dsl/)                                     | GDSL and DSLD support                                  |
| [ext/](../ext/)                                     | Spock, test discovery, and JUnit integrations          |
| [build-tool/](../build-tool/) and [bsp/](../bsp/)   | Build-tool and Build Server Protocol integration       |
| [refactor/openrewrite/](../refactor/openrewrite/)   | OpenRewrite recipes for CodeNarc fixes                 |
| [reports/](../reports/) and [schemas/](../schemas/) | Report parsing and shared protocol definitions         |

## Clients, tools, and tests

| Path                                                    | Responsibility                               |
| ------------------------------------------------------- | -------------------------------------------- |
| [editors/code/](../editors/code/)                       | VS Code and compatible-editor extension      |
| [jupyter/](../jupyter/)                                 | Groovy and Jenkins Jupyter kernels           |
| [repl/](../repl/)                                       | Groovy REPL support                          |
| [viz/](../viz/)                                         | AST model and desktop visualizer             |
| [tools/jenkins-extractor/](../tools/jenkins-extractor/) | Jenkins metadata extraction                  |
| [tests/](../tests/) and [test-utils/](../test-utils/)   | End-to-end scenarios and shared test support |

Module membership is defined in [settings.gradle.kts](../settings.gradle.kts). Architecture and design records live in
the [knowledge base](../kb/README.md); priorities live in the [Quality Bar](quality-bar.md).
