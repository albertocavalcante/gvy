# Run the standalone language server

Connect gvy to an editor with a Language Server Protocol (LSP) client. If you use VS Code or a compatible editor, the
[extension](../../editors/code/README.md#setup) bundles and starts the server for you.

## Install

You need **Java 17 or newer**. The server JAR includes its Groovy runtime; a separate Groovy installation is not
required to start it.

1. Download `gls-<version>.jar` from the [latest release](https://github.com/albertocavalcante/gvy/releases/latest).
2. Save it as `gls.jar` in a directory of your choice.
3. From that directory, verify that Java can start it:

   ```bash
   java -Xmx2g -jar gls.jar --help
   ```

The command prints the server's CLI help. The examples below use the renamed `gls.jar`; use its absolute path when
configuring an editor launched from another directory.

To build a JAR from source, follow the [contributor guide](../../CONTRIBUTING.md#build-and-run-the-server). Source
builds produce `gls/build/libs/gls-<version>-all.jar`, which differs from the release asset name.

## Connect over standard input/output

Configure your editor's LSP client to launch:

```bash
java -Xmx2g -jar gls.jar
```

Standard input/output (stdio) is the default transport. The process exchanges LSP messages with the editor; it does not
open an interactive prompt. No listening port is required.

## Connect over a socket

Start the server, then configure your LSP client to connect to `127.0.0.1:8080`:

```bash
java -Xmx2g -jar gls.jar lsp socket --port 8080 --host 127.0.0.1
```

The legacy `socket 8080` form also remains available. Without `--host`, socket mode binds the JVM's loopback address,
normally `127.0.0.1`. For IPv6 loopback, select `--host ::1` and configure the client to use that address.

Binding to a non-loopback address, such as `--host 0.0.0.0` in a container, exposes the unauthenticated server to the
network. Use it only on trusted networks.

## Hover

Source builds default to the native hover engine. It reuses the compiled document, including project types, Groovy
extension methods, and available Groovydoc. Hover follows the current open buffer; a request interrupted by an edit is
rejected instead of displaying stale information. Missing or ambiguous information produces a minimal card rather than a
guessed target.

In VS Code, `groovy.languageServer.engine` selects the engine. Existing explicit `core` settings remain supported;
select `native` and restart the language server to use compiler-backed hover. Other LSP clients can pass
`"groovy.languageServer.engine": "native"` in initialization options. Released versions may use an earlier default.

## Check support and troubleshoot

- Check [LSP feature support](feature-support.md) for protocol methods and known limits.
- Query [runtime facts](runtime-facts.md) when a client needs the running JVM and current hover-engine state.
- If Java cannot open the JAR, check `java -version` and the absolute JAR path configured in your editor.
- For socket connections, check that the client's address and port match the server's, including the IP address family.
- For extension settings and logs, use the [editor guide](../../editors/code/README.md#troubleshooting).
- To report a problem, include reproduction steps, a small Groovy example, and your server, Java, and editor versions in
  a [GitHub issue](https://github.com/albertocavalcante/gvy/issues).

Contributor debugging notes live in the [knowledge base](../../kb/TROUBLESHOOTING.md).
