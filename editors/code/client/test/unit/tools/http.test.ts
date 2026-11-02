import * as assert from "assert";
import { EventEmitter } from "events";
import proxyquire from "proxyquire";

/** Minimal stand-in for https.get whose response dies mid-body. */
function fakeHttps(statusCode: number, onOptions?: (o: Record<string, unknown>) => void) {
  return {
    get: (options: Record<string, unknown>, callback: (res: EventEmitter) => void) => {
      onOptions?.(options);
      const request = Object.assign(new EventEmitter(), {
        setTimeout: () => request,
        destroy: () => {},
      });
      const response = Object.assign(new EventEmitter(), {
        statusCode,
        statusMessage: "",
        headers: {},
        resume: () => {},
        setEncoding: () => {},
      });
      setImmediate(() => {
        callback(response);
        response.emit("data", Buffer.from("partial"));
        const reset = Object.assign(new Error("aborted"), { code: "ECONNRESET" });
        response.emit("error", reset);
      });
      return request;
    },
  };
}

describe("tools/http", () => {
  it("rejects instead of crashing when the response stream errors", async () => {
    const http = proxyquire.noCallThru()("../../../../tools/http.js", {
      "node:https": fakeHttps(200),
    });
    await assert.rejects(http.fetchText("https://example.test/x"), /aborted/);
  });

  it("rejects when an error response dies mid-body", async () => {
    const http = proxyquire.noCallThru()("../../../../tools/http.js", {
      "node:https": fakeHttps(500),
    });
    await assert.rejects(
      http.downloadToFile("https://example.test/x", "/nonexistent/never-written"),
      /aborted/,
    );
  });

  it("keeps a non-default port", async () => {
    let seen: Record<string, unknown> | undefined;
    const http = proxyquire.noCallThru()("../../../../tools/http.js", {
      "node:https": fakeHttps(200, (o) => (seen = o)),
    });
    await assert.rejects(http.fetchText("https://example.test:8443/x"));
    assert.strictEqual(seen?.port, "8443");
  });
});
