import { expect } from "chai";
import { PassThrough } from "stream";
import {
  createStderrRouter,
  pipeServerStderr,
  type LogSink,
} from "../../../src/server/stderrRouter";

type Entry = [string, string];

function recordingSink(): { sink: LogSink; entries: Entry[] } {
  const entries: Entry[] = [];
  const at = (level: string) => (message: string) =>
    entries.push([level, message]);
  return {
    entries,
    sink: {
      trace: at("trace"),
      debug: at("debug"),
      info: at("info"),
      warn: at("warn"),
      error: at("error"),
    },
  };
}

describe("stderrRouter", () => {
  it("keeps the Logback severity of each server line", () => {
    const { sink, entries } = recordingSink();
    const route = createStderrRouter(sink);

    route("14:03:11.123 [main] INFO c.g.a.gvy.gls.Server -- started");
    route("14:03:11.124 [main] WARN c.g.a.gvy.gls.Server -- slow import");
    route("14:03:11.125 [pool-1] ERROR c.g.a.gvy.gls.Server -- boom");
    route("14:03:11.126 [main] DEBUG c.g.a.gvy.gls.Server -- detail");
    route("14:03:11.127 [main] TRACE c.g.a.gvy.gls.Server -- finer");

    expect(entries.map(([level]) => level)).to.deep.equal([
      "info",
      "warn",
      "error",
      "debug",
      "trace",
    ]);
  });

  it("does not label routine INFO output as an error", () => {
    const { sink, entries } = recordingSink();
    createStderrRouter(sink)("14:03:11.123 [main] INFO x.Y -- hello");
    expect(entries.some(([level]) => level === "error")).to.equal(false);
  });

  it("gives exception continuation lines the severity of their header", () => {
    const { sink, entries } = recordingSink();
    const route = createStderrRouter(sink);

    route("14:03:11.125 [main] ERROR x.Y -- failed");
    route("java.lang.IllegalStateException: nope");
    route("\tat x.Y.run(Y.kt:10)");
    route("14:03:11.200 [main] INFO x.Y -- recovered");
    route("\tat not.really.a.trace(Z.kt:1)");

    expect(entries.map(([level]) => level)).to.deep.equal([
      "error",
      "error",
      "error",
      "info",
      "info",
    ]);
  });

  it("classifies Logback status lines printed before configuration", () => {
    const { sink, entries } = recordingSink();
    const route = createStderrRouter(sink);

    route("14:03:10,001 |-INFO in ch.qos.logback.classic.LoggerContext - init");
    route("14:03:10,002 |-WARN in ch.qos.logback.core - mismatch");
    route("14:03:10,003 |-ERROR in ch.qos.logback.core - cannot open");

    expect(entries.map(([level]) => level)).to.deep.equal([
      "info",
      "warn",
      "error",
    ]);
  });

  it("surfaces unclassified output before any server log as an error", () => {
    const { sink, entries } = recordingSink();
    createStderrRouter(sink)("Error: Unable to access jarfile gls.jar");
    expect(entries).to.deep.equal([
      ["error", "Error: Unable to access jarfile gls.jar"],
    ]);
  });

  it("treats JVM WARNING lines as warnings", () => {
    const { sink, entries } = recordingSink();
    createStderrRouter(sink)("WARNING: A restricted method was called");
    expect(entries[0][0]).to.equal("warn");
  });

  it("ignores blank lines", () => {
    const { sink, entries } = recordingSink();
    createStderrRouter(sink)("");
    expect(entries).to.be.empty;
  });

  it("reassembles lines split across stream chunks", async () => {
    const { sink, entries } = recordingSink();
    const input = new PassThrough();
    pipeServerStderr(input, sink);

    input.write("14:03:11.123 [main] WA");
    input.write("RN x.Y -- split mess");
    input.write("age\n14:03:11.124 [main] INFO x.Y -- next\n");
    input.end();
    await new Promise((resolve) => input.on("close", resolve));
    await new Promise((resolve) => setImmediate(resolve));

    expect(entries).to.deep.equal([
      ["warn", "14:03:11.123 [main] WARN x.Y -- split message"],
      ["info", "14:03:11.124 [main] INFO x.Y -- next"],
    ]);
  });
});
