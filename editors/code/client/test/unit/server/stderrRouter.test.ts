// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { expect } from "chai";
import { readFileSync } from "fs";
import { PassThrough } from "stream";
import {
  createStderrRouter,
  pipeServerStderr,
  pipeServerStdout,
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

  it("keeps encoder throwable continuations at their DEBUG and WARN levels", () => {
    const fixture = process.env.GVY_ROUTING_FIXTURE;
    const text = fixture
      ? readFileSync(fixture, "utf8")
      : [
          "00:00:00.000 [main] DEBUG example.Logger -- signal",
          "\tExceptions: example.OddSignal",
          "\texample.OddSignal: signal detail",
          "\t\tat example.Signal.run(Signal.groovy:7)",
          "00:00:00.000 [main] WARN  example.Logger -- signal",
          "\tExceptions: example.OddSignal",
          "\texample.OddSignal: signal detail",
          "\t\tat example.Signal.run(Signal.groovy:7)",
          "",
        ].join("\n");
    const { sink, entries } = recordingSink();
    const route = createStderrRouter(sink);
    for (const line of text.split("\n")) route(line);
    route.flush();

    const warnIndex = entries.findIndex(([, line]) => /\] WARN\s/u.test(line));
    expect(entries.length).to.equal(text.trimEnd().split("\n").length);
    expect(entries.filter(([, line]) => line.includes("OddSignal")).length).to.be.greaterThan(1);
    expect(warnIndex).to.be.greaterThan(1);
    expect(entries.length - warnIndex).to.be.greaterThan(1);
    expect(entries.slice(0, warnIndex).every(([level]) => level === "debug")).to.equal(true);
    expect(entries.slice(warnIndex).every(([level]) => level === "warn")).to.equal(true);
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
    route("Caused by: java.io.IOException: disk");
    route("\t... 3 more");
    route("14:03:11.200 [main] INFO x.Y -- recovered");

    expect(entries.map(([level]) => level)).to.deep.equal([
      "error",
      "error",
      "error",
      "error",
      "error",
      "info",
    ]);
  });

  it("does not let INFO swallow a crash that bypasses Logback", () => {
    for (const crash of [
      'Exception in thread "main" java.lang.OutOfMemoryError: Java heap space',
      "# A fatal error has been detected by the Java Runtime Environment:",
      "Error: Could not find or load main class gls",
    ]) {
      const { sink, entries } = recordingSink();
      const route = createStderrRouter(sink);
      route("14:03:11.123 [main] INFO x.Y -- healthy");
      route(crash);
      expect(entries[1], crash).to.deep.equal(["error", crash]);
    }
  });

  it("keeps the exception line after a WARN header but not later plain lines", () => {
    const { sink, entries } = recordingSink();
    const route = createStderrRouter(sink);
    route("14:03:11.123 [main] WARN x.Y -- retrying");
    route("java.net.SocketTimeoutException: timed out");
    route("\tat x.Y.run(Y.kt:1)");
    route("something unrelated");
    expect(entries.map(([level]) => level)).to.deep.equal([
      "warn",
      "warn",
      "warn",
      "error",
    ]);
  });

  it("keeps a DEBUG header's level for its exception and stack frames", () => {
    const { sink, entries } = recordingSink();
    const route = createStderrRouter(sink);
    route("14:03:11.123 [main] DEBUG x.Y -- Failed to resolve receiver type");
    route("java.io.IOException: Stream closed");
    route("\tat x.Y.read(Y.kt:3)");
    route("\tat x.Y.run(Y.kt:10)");
    route("14:03:11.124 [main] INFO x.Y -- next");
    expect(entries.map(([level]) => level)).to.deep.equal([
      "debug",
      "debug",
      "debug",
      "debug",
      "info",
    ]);
  });

  it("keeps an INFO header's level for a nested exception chain", () => {
    const { sink, entries } = recordingSink();
    const route = createStderrRouter(sink);
    route("14:03:11.123 [main] INFO x.Y -- optional lookup failed");
    route("x.Y$LookupException: no such entry");
    route("\tat x.Y.lookup(Y.kt:3)");
    route("Caused by: java.lang.IllegalStateException: closed");
    route("\tat x.Z.get(Z.kt:7)");
    route("\t... 2 more");
    expect(entries.every(([level]) => level === "info")).to.equal(true);
  });

  it("still surfaces a crash or plain line right after any server header", () => {
    for (const header of [
      "14:03:11.123 [main] TRACE x.Y -- finer",
      "14:03:11.123 [main] DEBUG x.Y -- detail",
      "14:03:11.123 [main] INFO x.Y -- healthy",
      "14:03:11.123 [main] WARN x.Y -- slow",
    ]) {
      for (const line of [
        'Exception in thread "main" java.lang.OutOfMemoryError: Java heap space',
        "# A fatal error has been detected by the Java Runtime Environment:",
        "Error: Could not find or load main class gls",
        "something unrelated",
      ]) {
        const { sink, entries } = recordingSink();
        const route = createStderrRouter(sink);
        route(header);
        route(line);
        expect(entries[1], `${header} / ${line}`).to.deep.equal([
          "error",
          line,
        ]);
      }
    }
  });

  it("pairs java.util.logging two-line records with their level", () => {
    const { sink, entries } = recordingSink();
    const route = createStderrRouter(sink);
    const infoHeader =
      "Nov 10, 2026 3:00:00 PM org.eclipse.lsp4j.jsonrpc.RemoteEndpoint handleNotification";
    const severeHeader =
      "Nov 10, 2026 3:00:01 PM org.eclipse.lsp4j.jsonrpc.RemoteEndpoint handleRequest";
    route(infoHeader);
    route("INFO: Unsupported notification method: $/setTrace");
    route(severeHeader);
    route("SEVERE: Internal error: boom");
    route("java.lang.IllegalStateException: boom");
    route(
      "\tat org.eclipse.lsp4j.jsonrpc.RemoteEndpoint.handleRequest(X.java:1)",
    );
    expect(entries).to.deep.equal([
      ["info", infoHeader],
      ["info", "INFO: Unsupported notification method: $/setTrace"],
      ["error", severeHeader],
      ["error", "SEVERE: Internal error: boom"],
      ["error", "java.lang.IllegalStateException: boom"],
      [
        "error",
        "\tat org.eclipse.lsp4j.jsonrpc.RemoteEndpoint.handleRequest(X.java:1)",
      ],
    ]);
  });

  it("maps every java.util.logging level", () => {
    const header = "Jan 02, 2026 11:59:59 AM a.B c";
    const cases: Entry[] = [
      ["SEVERE: s", "error"],
      ["WARNING: w", "warn"],
      ["INFO: i", "info"],
      ["CONFIG: c", "info"],
      ["FINE: f", "debug"],
      ["FINER: f", "trace"],
      ["FINEST: f", "trace"],
    ];
    for (const [line, level] of cases) {
      const { sink, entries } = recordingSink();
      const route = createStderrRouter(sink);
      route(header);
      route(line);
      expect(entries, line).to.deep.equal([
        [level, header],
        [level, line],
      ]);
    }
  });

  it("does not let a java.util.logging INFO record hide a following crash", () => {
    const { sink, entries } = recordingSink();
    const route = createStderrRouter(sink);
    route("Nov 10, 2026 3:00:00 PM a.B c");
    route("INFO: fine");
    route('Exception in thread "main" java.lang.Error: x');
    expect(entries[2][0]).to.equal("error");
  });

  it("surfaces an unpaired java.util.logging header as an error", () => {
    const { sink, entries } = recordingSink();
    const route = createStderrRouter(sink);
    route("Nov 10, 2026 3:00:00 PM a.B c");
    route("14:03:11.124 [main] INFO x.Y -- next");
    expect(entries).to.deep.equal([
      ["error", "Nov 10, 2026 3:00:00 PM a.B c"],
      ["info", "14:03:11.124 [main] INFO x.Y -- next"],
    ]);
  });

  it("does not treat a bare INFO: line as a java.util.logging record", () => {
    const { sink, entries } = recordingSink();
    createStderrRouter(sink)("INFO: not from java.util.logging");
    expect(entries[0][0]).to.equal("error");
  });

  it("flushes a java.util.logging header left pending at stream end", async () => {
    const { sink, entries } = recordingSink();
    const input = new PassThrough();
    pipeServerStderr(input, sink);

    input.end("Nov 10, 2026 3:00:00 PM a.B c\n");
    await new Promise((resolve) => input.on("close", resolve));
    await new Promise((resolve) => setImmediate(resolve));

    expect(entries).to.deep.equal([["error", "Nov 10, 2026 3:00:00 PM a.B c"]]);
  });

  it("keeps a java.util.logging record's level for its exception", () => {
    for (const [levelLine, level] of [
      ["INFO: lookup failed", "info"],
      ["FINE: lookup failed", "debug"],
    ]) {
      const { sink, entries } = recordingSink();
      const route = createStderrRouter(sink);
      route("Nov 10, 2026 3:00:00 PM a.B c");
      route(levelLine);
      route("java.io.IOException: x");
      route("\tat a.B.c(B.java:1)");
      expect(
        entries.map(([entryLevel]) => entryLevel),
        levelLine,
      ).to.deep.equal([level, level, level, level]);
    }
  });

  it("surfaces a java.util.logging header followed by another header", () => {
    const { sink, entries } = recordingSink();
    const route = createStderrRouter(sink);
    route("Nov 10, 2026 3:00:00 PM a.B first");
    route("Nov 10, 2026 3:00:01 PM a.B second");
    route("INFO: second record");
    expect(entries).to.deep.equal([
      ["error", "Nov 10, 2026 3:00:00 PM a.B first"],
      ["info", "Nov 10, 2026 3:00:01 PM a.B second"],
      ["info", "INFO: second record"],
    ]);
  });

  it("gives an exception the level of the latest of two log headers", () => {
    const { sink, entries } = recordingSink();
    const route = createStderrRouter(sink);
    route("14:03:11.123 [main] ERROR x.Y -- failed");
    route("14:03:11.124 [main] DEBUG x.Y -- retry detail");
    route("java.io.IOException: x");
    expect(entries.map(([level]) => level)).to.deep.equal([
      "error",
      "debug",
      "debug",
    ]);
  });

  it("pairs a java.util.logging header across a blank line", () => {
    const { sink, entries } = recordingSink();
    const route = createStderrRouter(sink);
    route("Nov 10, 2026 3:00:00 PM a.B c");
    route("");
    route("INFO: hello");
    expect(entries).to.deep.equal([
      ["info", "Nov 10, 2026 3:00:00 PM a.B c"],
      ["info", "INFO: hello"],
    ]);
  });

  it("keeps a Logback status header's level for its exception", () => {
    const { sink, entries } = recordingSink();
    const route = createStderrRouter(sink);
    route("14:03:10,002 |-WARN in ch.qos.logback.core - cannot open file");
    route("java.io.FileNotFoundException: gls.log (Permission denied)");
    route("\tat java.base/java.io.FileOutputStream.open0(Native Method)");
    expect(entries.map(([level]) => level)).to.deep.equal([
      "warn",
      "warn",
      "warn",
    ]);
  });

  it("cannot tell a raw printStackTrace right after a header from a logged exception", () => {
    // Documented tradeoff: the server must log throwables through its logger.
    const { sink, entries } = recordingSink();
    const route = createStderrRouter(sink);
    route("14:03:11.123 [main] INFO x.Y -- formatting");
    route("java.lang.IllegalStateException: printed by printStackTrace()");
    expect(entries[1][0]).to.equal("info");
  });

  it("flushes a pending java.util.logging header when the stream is destroyed", async () => {
    const { sink, entries } = recordingSink();
    const input = new PassThrough();
    pipeServerStderr(input, sink);

    input.write("Nov 10, 2026 3:00:00 PM a.B c\n");
    await new Promise((resolve) => setImmediate(resolve));
    input.destroy();
    await new Promise((resolve) => input.on("close", resolve));
    await new Promise((resolve) => setImmediate(resolve));

    expect(entries).to.deep.equal([["error", "Nov 10, 2026 3:00:00 PM a.B c"]]);
  });

  it("logs a stderr stream failure instead of throwing", async () => {
    const { sink, entries } = recordingSink();
    const input = new PassThrough();
    pipeServerStderr(input, sink);

    input.write("Nov 10, 2026 3:00:00 PM a.B c\n");
    await new Promise((resolve) => setImmediate(resolve));
    input.destroy(new Error("EPIPE"));
    await new Promise((resolve) => input.on("close", resolve));
    await new Promise((resolve) => setImmediate(resolve));

    expect(entries).to.deep.equal([
      ["error", "Nov 10, 2026 3:00:00 PM a.B c"],
      ["error", "Reading server stderr failed: EPIPE"],
    ]);
  });

  it("accepts thread names containing brackets", () => {
    const { sink, entries } = recordingSink();
    createStderrRouter(sink)("14:03:11.123 [pool-1[worker]] INFO x.Y -- hi");
    expect(entries[0][0]).to.equal("info");
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

  it("bounds oversized records and resets severity before recovery", async () => {
    const { sink, entries } = recordingSink();
    const input = new PassThrough();
    pipeServerStderr(input, sink);
    input.write("Nov 10, 2026 3:00:00 PM a.B c\n");
    input.write("x".repeat(64 * 1024 + 1));
    input.end("\n  continuation\n14:03:11.123 [main] INFO x.Y -- recovered\n");
    await new Promise((resolve) => input.on("close", resolve));
    assertTruncated(entries);
  });

  it("keeps the level of truncated Logback records then resets it", async () => {
    for (const level of ["INFO", "DEBUG", "WARN"]) {
      const { sink, entries } = recordingSink();
      const input = new PassThrough();
      pipeServerStderr(input, sink);
      input.write(
        `14:03:11.123 [main] ${level} x.Y -- ${"x".repeat(64 * 1024)}\n`,
      );
      input.end(
        "  unrelated continuation\n14:03:11.124 [main] INFO x.Y -- recovered\n",
      );
      await new Promise((resolve) => input.on("close", resolve));
      expect(entries[0][0]).to.equal(level.toLowerCase());
      expect(entries[0][1]).to.include("truncated");
      expect(Buffer.byteLength(entries[0][1])).to.be.lessThan(64 * 1024 + 128);
      expect(entries[1]).to.deep.equal(["error", "  unrelated continuation"]);
      expect(entries[2][0]).to.equal("info");
    }
  });

  it("keeps a truncated stack continuation at its preceding severity", async () => {
    const { sink, entries } = recordingSink();
    const input = new PassThrough();
    pipeServerStderr(input, sink);
    input.write("14:03:11.123 [main] DEBUG x.Y -- failed\n");
    input.write(`\tat ${"x".repeat(64 * 1024)}\n`);
    input.end("  next continuation\n");
    await new Promise((resolve) => input.on("close", resolve));
    expect(entries.map(([level]) => level)).to.deep.equal([
      "debug",
      "debug",
      "error",
    ]);
    expect(entries[1][1]).to.include("truncated");
  });

  it("pairs a pending JUL header with a truncated level then resets", async () => {
    const { sink, entries } = recordingSink();
    const input = new PassThrough();
    pipeServerStderr(input, sink);
    input.write("Nov 10, 2026 3:00:00 PM a.B c\n");
    input.write(`INFO: ${"x".repeat(64 * 1024)}\n`);
    input.end("  continuation\n");
    await new Promise((resolve) => input.on("close", resolve));
    expect(entries.map(([level]) => level)).to.deep.equal([
      "info",
      "info",
      "error",
    ]);
    expect(entries[1][1]).to.include("truncated");
  });

  it("treats a truncated unpaired JUL header as an error", async () => {
    const { sink, entries } = recordingSink();
    const input = new PassThrough();
    pipeServerStderr(input, sink);
    input.write(`Nov 10, 2026 3:00:00 PM a.B ${"x".repeat(64 * 1024)}\n`);
    input.end("INFO: unrelated\n");
    await new Promise((resolve) => input.on("close", resolve));
    expect(entries.map(([level]) => level)).to.deep.equal(["error", "error"]);
    expect(entries[0][1]).to.include("truncated");
  });

  it("does not reuse stale severity after an unpaired JUL header", async () => {
    const { sink, entries } = recordingSink();
    const input = new PassThrough();
    pipeServerStderr(input, sink);
    input.write("14:03:11.123 [main] DEBUG x.Y -- previous\n");
    input.write("Nov 10, 2026 3:00:00 PM a.B c\n");
    input.end(`  ${"x".repeat(64 * 1024)}\n`);
    await new Promise((resolve) => input.on("close", resolve));
    expect(entries.map(([level]) => level)).to.deep.equal([
      "debug",
      "error",
      "error",
    ]);
  });

  it("treats an all-whitespace truncated prefix as ambiguous", async () => {
    const { sink, entries } = recordingSink();
    const input = new PassThrough();
    pipeServerStderr(input, sink);
    input.write("14:03:11.123 [main] INFO x.Y -- previous\n");
    input.end(`${" ".repeat(64 * 1024 + 1)}\n`);
    await new Promise((resolve) => input.on("close", resolve));
    expect(entries.map(([level]) => level)).to.deep.equal(["info", "error"]);
  });

  it("bounds auxiliary stdout, preserves UTF-8 and recovers", async () => {
    const { sink, entries } = recordingSink();
    const input = new PassThrough();
    pipeServerStdout(input, sink);
    const unicode = Buffer.from("🧪");
    input.write(unicode.subarray(0, 2));
    input.write(unicode.subarray(2));
    input.write("x".repeat(64 * 1024));
    input.end("\r\nnext");
    await new Promise((resolve) => input.on("close", resolve));
    expect(entries).to.have.length(2);
    expect(entries[0][0]).to.equal("info");
    expect(entries[0][1]).to.include("🧪");
    expect(entries[0][1]).to.include("truncated");
    expect(Buffer.byteLength(entries[0][1])).to.be.lessThan(64 * 1024 + 128);
    expect(entries[1]).to.deep.equal(["info", "next"]);
  });
});

function assertTruncated(entries: Entry[]): void {
  expect(entries[0]).to.deep.equal(["error", "Nov 10, 2026 3:00:00 PM a.B c"]);
  expect(entries[1][0]).to.equal("error");
  expect(entries[1][1]).to.include("truncated");
  expect(Buffer.byteLength(entries[1][1])).to.be.lessThan(64 * 1024 + 128);
  expect(entries[2]).to.deep.equal(["error", "  continuation"]);
  expect(entries[3]).to.deep.equal([
    "info",
    "14:03:11.123 [main] INFO x.Y -- recovered",
  ]);
}
