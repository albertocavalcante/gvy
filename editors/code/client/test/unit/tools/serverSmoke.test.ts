import * as assert from "node:assert";
import { EventEmitter } from "node:events";
import proxyquire from "proxyquire";

describe("bounded LSP smoke", () => {
  function fixture(mode: "success" | "silent" | "invalid") {
    const child = Object.assign(new EventEmitter(), {
      stdin: Object.assign(new EventEmitter(), {
        write: (_text: string) => true,
      }),
      stdout: new EventEmitter(),
      stderr: new EventEmitter(),
      killed: false,
      kill: (_signal: string) => {
        child.killed = true;
        return true;
      },
    });
    const reply = (message: object) => {
      const json = JSON.stringify({ jsonrpc: "2.0", ...message });
      child.stdout.emit(
        "data",
        Buffer.from(
          `Content-Length: ${Buffer.byteLength(json)}\r\n\r\n${json}`,
        ),
      );
    };
    child.stdin.write = (text) => {
      const message = JSON.parse(text.slice(text.indexOf("\r\n\r\n") + 4)) as {
        method: string;
      };
      queueMicrotask(() => {
        if (mode === "silent") return;
        if (mode === "invalid") {
          child.stdout.emit(
            "data",
            Buffer.from("not a protocol header\r\n\r\n"),
          );
          return;
        }
        if (message.method === "initialize")
          reply({ id: 1, result: { capabilities: {} } });
        if (message.method === "shutdown") reply({ id: 2, result: null });
        if (message.method === "exit") child.emit("exit", 0);
      });
      return true;
    };
    const module = proxyquire("../../../../tools/smoke-server.js", {
      "node:child_process": { spawn: () => child },
    }) as { smokeServer: (jar: string, timeout: number) => Promise<void> };
    return { child, run: () => module.smokeServer("fixture.jar", 20) };
  }

  it("completes initialize, shutdown and exit", async () => {
    const { child, run } = fixture("success");
    await run();
    assert.strictEqual(child.killed, false);
  });
  it("kills a silent server within the configured deadline", async () => {
    const { child, run } = fixture("silent");
    await assert.rejects(run(), /timed out/);
    assert.strictEqual(child.killed, true);
  });
  it("rejects invalid stdout without reporting a ready server", async () => {
    const { child, run } = fixture("invalid");
    await assert.rejects(run(), /Invalid LSP header/);
    assert.strictEqual(child.killed, true);
  });
});
