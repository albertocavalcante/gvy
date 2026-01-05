# LSP Feature Support

Groovy LSP targets Language Server Protocol (LSP) 3.17. The matrix below is based on the official specification and the
current server capabilities and implementations.

Legend:

- yes: supported and advertised to clients
- partial: implemented but limited or not advertised yet
- planned: on the roadmap, not implemented
- no: not implemented

## Lifecycle

| Method        | Support | Notes                           |
| ------------- | ------- | ------------------------------- |
| `initialize`  | yes     | Initializes server capabilities |
| `initialized` | yes     | Kicks off background setup      |
| `shutdown`    | yes     | Graceful shutdown               |
| `exit`        | yes     | Process exit                    |

## Text document sync

| Method                           | Support | Notes                                |
| -------------------------------- | ------- | ------------------------------------ |
| `textDocument/didOpen`           | yes     | Triggers compilation and diagnostics |
| `textDocument/didChange`         | yes     | Full sync only                       |
| `textDocument/didSave`           | yes     |                                      |
| `textDocument/didClose`          | yes     | Clears diagnostics                   |
| `textDocument/willSave`          | no      |                                      |
| `textDocument/willSaveWaitUntil` | no      |                                      |
| `textDocument/didRename`         | no      |                                      |

## Language features

| Method                                   | Support | Notes                                      |
| ---------------------------------------- | ------- | ------------------------------------------ |
| `textDocument/completion`                | yes     | Keywords + AST-based symbols               |
| `completion/resolve`                     | no      |                                            |
| `textDocument/hover`                     | yes     | Type info + documentation                  |
| `textDocument/signatureHelp`             | yes     | Method parameter hints                     |
| `textDocument/definition`                | yes     |                                            |
| `textDocument/typeDefinition`            | yes     |                                            |
| `textDocument/implementation`            | partial | Implemented; capability not advertised yet |
| `textDocument/references`                | yes     |                                            |
| `textDocument/documentSymbol`            | yes     |                                            |
| `textDocument/documentHighlight`         | yes     |                                            |
| `textDocument/rename`                    | yes     | No `prepareRename` support                 |
| `textDocument/prepareRename`             | no      |                                            |
| `textDocument/codeAction`                | yes     | Quick fixes + formatting                   |
| `codeAction/resolve`                     | no      |                                            |
| `textDocument/codeLens`                  | yes     | Test run/debug lenses                      |
| `codeLens/resolve`                       | no      |                                            |
| `textDocument/foldingRange`              | yes     |                                            |
| `textDocument/formatting`                | yes     | OpenRewrite                                |
| `textDocument/rangeFormatting`           | no      |                                            |
| `textDocument/onTypeFormatting`          | no      |                                            |
| `textDocument/semanticTokens/full`       | partial | Jenkins pipeline focus                     |
| `textDocument/semanticTokens/range`      | no      |                                            |
| `textDocument/semanticTokens/full/delta` | no      |                                            |
| `textDocument/inlayHint`                 | no      |                                            |
| `textDocument/inlineValue`               | no      |                                            |
| `textDocument/selectionRange`            | no      |                                            |
| `textDocument/documentLink`              | no      |                                            |
| `textDocument/documentColor`             | no      |                                            |
| `textDocument/colorPresentation`         | no      |                                            |
| `textDocument/declaration`               | no      |                                            |
| `textDocument/linkedEditingRange`        | no      |                                            |
| `textDocument/moniker`                   | no      |                                            |
| `textDocument/prepareCallHierarchy`      | no      |                                            |
| `callHierarchy/incomingCalls`            | no      |                                            |
| `callHierarchy/outgoingCalls`            | no      |                                            |
| `textDocument/prepareTypeHierarchy`      | no      |                                            |
| `typeHierarchy/supertypes`               | no      |                                            |
| `typeHierarchy/subtypes`                 | no      |                                            |

## Diagnostics

| Method                            | Support | Notes                            |
| --------------------------------- | ------- | -------------------------------- |
| `textDocument/publishDiagnostics` | yes     | Push-based diagnostics           |
| `textDocument/diagnostic`         | no      | Pull diagnostics not implemented |
| `workspace/diagnostic`            | no      | Pull diagnostics not implemented |

## Workspace

| Method                                | Support | Notes                                         |
| ------------------------------------- | ------- | --------------------------------------------- |
| `workspace/symbol`                    | yes     |                                               |
| `workspace/executeCommand`            | partial | `groovy.version` handler only; not advertised |
| `workspace/didChangeConfiguration`    | yes     |                                               |
| `workspace/didChangeWatchedFiles`     | yes     | Registered dynamically when supported         |
| `workspace/didChangeWorkspaceFolders` | no      |                                               |
| `workspace/configuration`             | no      |                                               |
| `workspace/applyEdit`                 | no      |                                               |
| `workspace/willCreateFiles`           | no      |                                               |
| `workspace/didCreateFiles`            | no      |                                               |
| `workspace/willRenameFiles`           | no      |                                               |
| `workspace/didRenameFiles`            | no      |                                               |
| `workspace/willDeleteFiles`           | no      |                                               |
| `workspace/didDeleteFiles`            | no      |                                               |
| `workspace/codeLens/refresh`          | no      |                                               |
| `workspace/semanticTokens/refresh`    | no      |                                               |
| `workspace/inlayHint/refresh`         | no      |                                               |

## Window and telemetry

| Method                           | Support | Notes                             |
| -------------------------------- | ------- | --------------------------------- |
| `window/showMessage`             | yes     | Startup and error notifications   |
| `window/showMessageRequest`      | no      |                                   |
| `window/logMessage`              | no      |                                   |
| `window/workDoneProgress/create` | yes     | Dependency resolution progress    |
| `$/progress`                     | yes     | WorkDoneProgress updates          |
| `telemetry/event`                | yes     | Formatting + definition telemetry |

## Notebook documents

| Method                       | Support | Notes |
| ---------------------------- | ------- | ----- |
| `notebookDocument/didOpen`   | no      |       |
| `notebookDocument/didChange` | no      |       |
| `notebookDocument/didSave`   | no      |       |
| `notebookDocument/didClose`  | no      |       |

## Capability alignment notes

- `textDocument/implementation` is implemented but not yet advertised in server capabilities.
- `workspace/executeCommand` is implemented for `groovy.version` only, but not advertised.
