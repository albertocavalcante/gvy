// @ts-check
const { spawn } = require("node:child_process");
const path = require("node:path");

/** Verify the actual LSP transport, including graceful shutdown.
 * @param {string} jar @param {number} timeoutMs
 * @returns {Promise<void>}
 */
function smokeServer(jar, timeoutMs = 30000) {
  const java = process.env.JAVA_HOME
    ? path.join(
        process.env.JAVA_HOME,
        "bin",
        process.platform === "win32" ? "java.exe" : "java",
      )
    : "java";
  return smokeServerCommand(java, ["-Xmx2g", "-jar", jar], timeoutMs);
}

/** Verify an LSP command with inherited application environment.
 * @param {string} command @param {string[]} args @param {number} timeoutMs
 * @param {NodeJS.ProcessEnv} env
 * @param {{uri: string, text: string, verifySymbols: (symbols: object[]) => void} | null} document
 * @returns {Promise<void>}
 */
function smokeServerCommand(
  command,
  args,
  timeoutMs = 30000,
  env = process.env,
  document = null,
) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      stdio: ["pipe", "pipe", "pipe"],
      env,
    });
    let buffer = Buffer.alloc(0);
    let stderr = "";
    let initialized = false;
    let shutdown = false;
    let documentChecked = false;
    let settled = false;
    const timer = setTimeout(
      () => fail(new Error("LSP smoke timed out")),
      timeoutMs,
    );
    /** @param {Error} error */
    function fail(error) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      child.kill("SIGKILL");
      reject(new Error(`${error.message}\n${stderr}`, { cause: error }));
    }
    /** @param {object} message */
    function send(message) {
      const json = JSON.stringify({ jsonrpc: "2.0", ...message });
      child.stdin.write(
        `Content-Length: ${Buffer.byteLength(json)}\r\n\r\n${json}`,
      );
    }
    child.on("error", fail);
    child.stdin.on("error", fail);
    child.stderr.on("data", (chunk) => {
      stderr = (stderr + chunk.toString()).slice(-8192);
    });
    child.stdout.on("data", (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      if (buffer.length > 8 * 1024 * 1024)
        return fail(new Error("LSP output exceeded smoke-test limit"));
      try {
        while (true) {
          const headerEnd = buffer.indexOf("\r\n\r\n");
          if (headerEnd < 0) return;
          const length = /Content-Length: (\d+)/i.exec(
            buffer.subarray(0, headerEnd).toString(),
          );
          if (!length) throw new Error("Invalid LSP header");
          const end = headerEnd + 4 + Number(length[1]);
          if (buffer.length < end) return;
          const message = JSON.parse(
            buffer.subarray(headerEnd + 4, end).toString(),
          );
          buffer = buffer.subarray(end);
          if (message.id === 1 && !message.method) {
            if (message.error || !message.result?.capabilities)
              throw new Error("LSP initialize failed");
            initialized = true;
            send({ method: "initialized", params: {} });
            if (document) {
              send({
                method: "textDocument/didOpen",
                params: {
                  textDocument: {
                    uri: document.uri,
                    languageId: "groovy",
                    version: 1,
                    text: document.text,
                  },
                },
              });
              send({
                id: 3,
                method: "textDocument/documentSymbol",
                params: { textDocument: { uri: document.uri } },
              });
            } else {
              send({ id: 2, method: "shutdown", params: null });
            }
          } else if (message.id === 3 && !message.method && document) {
            if (message.error || !Array.isArray(message.result))
              throw new Error("LSP document symbols failed");
            document.verifySymbols(message.result);
            documentChecked = true;
            send({ id: 2, method: "shutdown", params: null });
          } else if (message.id === 2 && !message.method) {
            if (message.error) throw new Error("LSP shutdown failed");
            shutdown = true;
            send({ method: "exit" });
          } else if (message.method && message.id !== undefined) {
            send({
              id: message.id,
              result: message.method === "workspace/configuration" ? [] : null,
            });
          }
        }
      } catch (error) {
        fail(error instanceof Error ? error : new Error(String(error)));
      }
    });
    child.on("exit", (code) => {
      if (settled) return;
      if (
        !initialized ||
        !shutdown ||
        (document && !documentChecked) ||
        code !== 0
      )
        return fail(
          new Error(`LSP exited before completing smoke test (${code})`),
        );
      settled = true;
      clearTimeout(timer);
      resolve();
    });
    send({
      id: 1,
      method: "initialize",
      params: { processId: process.pid, rootUri: null, capabilities: {} },
    });
  });
}

module.exports = { smokeServer, smokeServerCommand };
if (require.main === module) {
  if (!process.argv[2])
    throw new Error("Usage: node tools/smoke-server.js <server.jar>");
  smokeServer(process.argv[2]).then(
    () => console.error("LSP initialize/shutdown smoke passed"),
    (error) => {
      console.error(error.message);
      process.exitCode = 1;
    },
  );
}
