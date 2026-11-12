# gvy Zed adapter (local experiment)

This is a provisional, grammar-free development extension for plain `.groovy` files. It launches a locally supplied gvy
JAR over stdio. The extension ID `groovy-gvy` and server ID `gvy` are provisional. Grammar-free loading, editing
features, trust behavior and platform support require explicit validation in each target environment; this is not a
registry release or a broad feature-support claim.

## Prepare and install

1. Build or select a gvy server JAR and record its revision and checksum. The JAR and Java must be accessible on the
   project host. Supply Java 17 or newer; verify the selected executable with `java -version` or its absolute path.
2. In Zed 1.28.1, open Extensions, choose **Install Dev Extension**, and select this directory. The archived Groovy
   extension is not required; disable it for the independent-loading experiment.
3. In the project's `.zed/settings.json`, replace the placeholder with the actual **absolute** host path to the JAR
   (JSON does not expand `$HOME`):

```json
{
  "lsp": {
    "gvy": {
      "settings": {
        "launch": {
          "jar_path": "<absolute path to gvy server jar>"
        },
        "server": {
          "groovy.server.maxNumberOfProblems": 100
        }
      }
    }
  },
  "languages": {
    "Groovy": {
      "language_servers": ["gvy"],
      "semantic_tokens": "full",
      "document_symbols": "on",
      "document_folding_ranges": "on"
    }
  }
}
```

Optional `launch.java_path` is an absolute executable path. Otherwise the adapter uses the worktree shell's
`JAVA_HOME/bin/java`, then its `PATH` lookup. An explicit path or nonempty `JAVA_HOME` does not fall back when invalid.
Optional `launch.jvm_args` replaces the default `["-Xmx2g"]`; each array item is one argument. The adapter appends
`-jar` and the JAR path without a shell. Paths and arguments may contain spaces and Unicode. The current path validation
is scoped to absolute Unix paths; other platforms remain untested.

`server` is an object of canonical flat `groovy.*` keys and values, including arrays and objects. The adapter supplies
it for initialization and workspace configuration; launch settings are never part of that server map. Non-null raw
`lsp.gvy.initialization_options` is rejected: Zed applies it at initialization but immediately sends the workspace
configuration, which would replace its values. Put startup and live server configuration in `settings.server`. The
adapter checks the chosen Java executable through the worktree's executable lookup before launch, but cannot guarantee
it remains available until the process starts. It does not check Java's version or verify arbitrary host JAR paths from
WASM. Check Java 17 or newer and the JAR independently; use Zed's language-server logs for process and JAR startup
errors.

## Build and test in Zed

From the repository root, run `make zed-check zed-build`. These use the pinned Rust toolchain to format-check, lint,
test and build the WASI extension. The build does not install the extension into Zed. Copy the
[basic fixture](fixtures/README.md) to a scratch project and configure it as described above.

Install the dev extension from `editors/zed` using Zed's **Install Dev Extension** action. After changing the extension,
run the root checks again and use the extension's rebuild action in Zed. Verify server reconnection after rebuilding. If
needed, save the scratch buffers, close only that project window, then reopen the scratch folder and `Main.groovy`. This
recovered the server in one Zed 1.28.1 run whose scratch directory was ignored by Git; the restart action on those
existing buffers alone did not reconnect. Use a scratch project outside the checkout for automatic LSP checks and record
the behavior in your run. Keep the old Groovy extension disabled for the independent-loading check.

Record Zed version and channel, extension revision and dirty state, SDK/toolchain, JAR source revision and checksum,
Java version, platform, project trust state, and the exact settings used with private paths redacted. Record the action,
observed result, logs or screenshots, and whether the buffer was saved for each check. The expectations below are test
targets, not claims that every environment supports them.

| Check                      | Action and expected result                                                                                                                                                 | Evidence to record                                                                              |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| Loading and trust          | Open the scratch `.groovy` files without the legacy extension. In restricted mode check that the server has not started; trust only the scratch project and check startup. | Selected language and server, trust state, startup log or process observation.                  |
| Completion                 | In `Main.groovy`, type `greeter.gre` and request completion; expect `greet(String)` and insertion of a usable call.                                                        | Suggestions, inserted text and unsaved state.                                                   |
| Hover and definition       | Hover over `greet` and navigate to its definition; expect method information and the declaration in `Greeter.groovy`.                                                      | Hover content and destination.                                                                  |
| Diagnostics                | Introduce an incomplete expression without saving, then repair it; expect diagnostics to change and clear.                                                                 | Edit, diagnostics and buffer state before and after repair.                                     |
| Highlighting and structure | Inspect semantic highlighting, document symbols and folding with the sample and incomplete edits.                                                                          | Observed tokens, symbols and ranges, including missing or stale results.                        |
| Lifecycle and isolation    | Rebuild or restart the server, reopen the project if needed, and open a second scratch project. Expect each project to recover and use its own configuration.              | Reconnection steps, server logs and project separation; record failures.                        |
| Configuration and failures | Exercise the server settings and test invalid Java and JAR paths, paths with spaces, and Unicode identifiers. Expect actionable failures and intact paths.                 | Settings with paths redacted, errors and behavior; distinguish tested cases from untested ones. |

Do not infer trust enforcement, language support or feature completeness from this checklist alone. Record actual
results and limitations separately. Known Unicode position and semantic-token work is tracked in issues #1190 and #121.

The language configuration supplies static `{}`, `[]` and `()` pairs. With Zed's bracket autoclose enabled, typing an
opening delimiter at the end of a line inserts its closer. While inside that automatically inserted pair, typing the
closer skips it, and Backspace inside the empty pair removes both. At other insertion positions, Zed checks the
character after the cursor. Return inside an empty pair adds a line for the closer. These operations also work with the
language server disabled. They do not provide syntax-aware exclusions: delimiters also close inside comments and
strings. Quotes are not paired. In the tested grammar-free configuration, Return did not indent the new body line, even
with `auto_indent: true` and `tab_size: 4`; use explicit indentation. Syntax selection and scope-aware editing remain
experiment gates in #1175.
