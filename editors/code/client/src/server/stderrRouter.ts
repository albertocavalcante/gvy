// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { pipeBoundedLines } from "../utils/boundedLines";
import type { Readable } from "stream";

/** The severity methods of a VS Code `LogOutputChannel` that we use. */
export interface LogSink {
  trace(message: string): void;
  debug(message: string): void;
  info(message: string): void;
  warn(message: string): void;
  error(message: string): void;
}

/** Routes one stderr line; `flush` emits a line still held for pairing. */
export interface StderrRouter {
  (line: string): void;
  flush(): void;
  truncated(line: string): void;
}

type Level = "trace" | "debug" | "info" | "warn" | "error";

// The server logs `HH:mm:ss.SSS [thread] LEVEL logger -- message` (gls logback.xml).
const SERVER_LINE =
  /^\d{2}:\d{2}:\d{2}\.\d{3} \[.*?\] (TRACE|DEBUG|INFO|WARN|ERROR)\b/u;
// Logback's own status output before configuration: `HH:mm:ss,SSS |-LEVEL in ...`.
const LOGBACK_STATUS = /^\d{2}:\d{2}:\d{2},\d{3} \|-(INFO|WARN|ERROR) in\b/u;
const JVM_WARNING = /^WARNING:/u;

// lsp4j logs through java.util.logging, which the server does not bridge to
// Logback. Its default SimpleFormatter writes each record on two lines:
// `Nov 10, 2026 3:00:00 PM source method`, then `LEVEL: message`.
// NOTE: Only the English level names are recognized; other locales keep the
// pre-pairing behavior (both lines are errors).
const JUL_HEADER = /^\S+ \d{1,2}, \d{4} \d{1,2}:\d{2}:\d{2} \S+ \S/u;
const JUL_LEVELS = {
  SEVERE: "error",
  WARNING: "warn",
  INFO: "info",
  CONFIG: "info",
  FINE: "debug",
  FINER: "trace",
  FINEST: "trace",
} as const satisfies Record<string, Level>;
const JUL_LEVEL = new RegExp(`^(${Object.keys(JUL_LEVELS).join("|")}): `, "u");

function julLevelOf(line: string): Level | undefined {
  const name = JUL_LEVEL.exec(line)?.[1];
  return name !== undefined && isJulLevelName(name)
    ? JUL_LEVELS[name]
    : undefined;
}

function isJulLevelName(name: string): name is keyof typeof JUL_LEVELS {
  return Object.hasOwn(JUL_LEVELS, name);
}

function levelOf(line: string): Level | undefined {
  const server = SERVER_LINE.exec(line) ?? LOGBACK_STATUS.exec(line);
  if (server) {
    return server[1].toLowerCase() as Level;
  }
  return JVM_WARNING.test(line) ? "warn" : undefined;
}

// Stack trace parts that follow an exception header.
const CONTINUATION = /^(\s|at |Caused by:|Suppressed:|\.\.\. \d+ more)/u;
// `Throwable.toString()` of a logged exception: `pkg.Name[: message]`. It
// rejects `Exception in thread ...`, which reports an uncaught crash.
const EXCEPTION_LINE = /^[\w$.]+(Exception|Error|Throwable)(: |$)/u;

/**
 * Routes each server stderr line to the matching log severity. The
 * language client would log every stderr line as an error, which makes a
 * healthy session look like a failing one. Stack trace continuations
 * (indented lines, `at`, `Caused by:`, `Suppressed:`, `... N more`) and an
 * exception line right after a log header keep that header's level, so a
 * `logger.debug(msg, e)` trace stays debug. A java.util.logging header takes
 * the level of the `LEVEL:` line after it. Any other line without a level
 * (JVM fatal errors, uncaught `Exception in thread`, launcher errors) stays
 * an error, even right after healthy output.
 *
 * Tradeoff: stderr carries no record boundaries, so a raw
 * `Throwable.printStackTrace()` written right after a header looks exactly
 * like that header's logged exception and takes its level. The server must
 * therefore report throwables through its logger, never `printStackTrace()`.
 */
export function createStderrRouter(sink: LogSink): StderrRouter {
  let current: Level = "error";
  let exceptionLineNext = false;
  let julHeader: string | undefined;

  const flush = () => {
    if (julHeader !== undefined) {
      sink.error(julHeader);
      julHeader = undefined;
    }
  };

  const route = (line: string) => {
    if (line.trim() === "") {
      return;
    }
    if (julHeader !== undefined) {
      const julLevel = julLevelOf(line);
      if (julLevel !== undefined) {
        current = julLevel;
        exceptionLineNext = true;
        sink[current](julHeader);
        julHeader = undefined;
        sink[current](line);
        return;
      }
      flush();
    }
    if (JUL_HEADER.test(line)) {
      julHeader = line;
      return;
    }
    const level = levelOf(line);
    if (level) {
      current = level;
      exceptionLineNext = true;
    } else if (
      CONTINUATION.test(line) ||
      (exceptionLineNext && EXCEPTION_LINE.test(line))
    ) {
      // Keep `current`.
      exceptionLineNext = false;
    } else {
      current = "error";
      exceptionLineNext = false;
    }
    sink[current](line);
  };
  const reset = () => {
    flush();
    current = "error";
    exceptionLineNext = false;
  };
  const truncated = (line: string) => {
    // An unpaired JUL header does not establish a severity for this line.
    if (julHeader !== undefined && julLevelOf(line) === undefined) reset();
    if (line.trim() === "") {
      reset();
      sink.error(line + TRUNCATION_MARKER);
    } else {
      route(line + TRUNCATION_MARKER);
    }
    reset();
  };
  return Object.assign(route, { flush, truncated });
}

/** Extra bytes beyond the payload limit are discarded; the marker is 28 bytes. */
const TRUNCATION_MARKER = " [diagnostic line truncated]";

/** Feeds bounded server stderr lines to the severity router. */
export function pipeServerStderr(input: Readable, sink: LogSink): void {
  const route = createStderrRouter(sink);
  pipeBoundedLines(
    input,
    ({ text, truncated }) => {
      if (truncated) {
        route.truncated(text);
      } else {
        route(text);
      }
    },
    (error) => {
      route.flush();
      sink.error(`Reading server stderr failed: ${error.message}`);
    },
  );
  // A JUL header can remain pending after the last complete line.
  input.once("end", route.flush);
  input.once("close", route.flush);
}

/** Used only for auxiliary stdout on non-stdio language-client transports. */
export function pipeServerStdout(input: Readable, sink: LogSink): void {
  pipeBoundedLines(
    input,
    ({ text, truncated }) => {
      sink.info(truncated ? text + TRUNCATION_MARKER : text);
    },
    (error) => sink.error(`Reading server stdout failed: ${error.message}`),
  );
}
