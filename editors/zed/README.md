# gvy Zed adapter (local experiment)

This is a provisional, grammar-free development extension for plain `.groovy` files. It launches a locally supplied gvy
JAR over stdio. The extension ID `groovy-gvy` and server ID `gvy` are provisional. Grammar-free loading, editing
features, trust behavior and platform support still require testing in Zed; this is not a registry release or a
feature-support claim.

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
adapter cannot verify arbitrary host JAR paths from WASM and does not check Java's version at launch. Check the Java
executable and JAR independently; use Zed's language-server logs for process and JAR startup errors.

## Checks and experiment

From this directory, `cargo fmt --check`, `cargo clippy --locked --all-targets
-- -D warnings`, `cargo test --locked`,
and `cargo build --locked --release
--target wasm32-wasip2` use the pinned Rust toolchain. Limit build concurrency with
`CARGO_BUILD_JOBS=2` on the shared machine.

Open a small Groovy project and record the exact Zed, extension, JAR and Java identities. Verify language loading
without a grammar or legacy extension, then exercise completion, hover, definition and diagnostics after unsaved edits.
Separately check semantic tokens, outline, folding, incomplete code, Unicode, spaces in paths, invalid Java/JAR,
restart, worktree trust, and two projects. Record observed failures as well as successes before making support claims.
