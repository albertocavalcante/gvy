import * as readline from "readline";
import type { Readable } from "stream";

/** The severity methods of a VS Code `LogOutputChannel` that we use. */
export interface LogSink {
  trace(message: string): void;
  debug(message: string): void;
  info(message: string): void;
  warn(message: string): void;
  error(message: string): void;
}

type Level = "trace" | "debug" | "info" | "warn" | "error";

// The server logs `HH:mm:ss.SSS [thread] LEVEL logger -- message` (gls logback.xml).
const SERVER_LINE = /^\d{2}:\d{2}:\d{2}\.\d{3} \[[^\]]*\] (TRACE|DEBUG|INFO|WARN|ERROR)\b/u;
// Logback's own status output before configuration: `HH:mm:ss,SSS |-LEVEL in ...`.
const LOGBACK_STATUS = /^\d{2}:\d{2}:\d{2},\d{3} \|-(INFO|WARN|ERROR) in\b/u;
const JVM_WARNING = /^WARNING:/u;

function levelOf(line: string): Level | undefined {
  const server = SERVER_LINE.exec(line) ?? LOGBACK_STATUS.exec(line);
  if (server) {
    return server[1].toLowerCase() as Level;
  }
  return JVM_WARNING.test(line) ? "warn" : undefined;
}

/**
 * Routes each server stderr line to the matching log severity. The
 * language client would log every stderr line as an error, which makes a
 * healthy session look like a failing one. Lines without their own level
 * (exception traces) keep the level of the line before them; output that
 * appears before any server log line is unclassified and stays an error so
 * startup failures remain visible.
 */
export function createStderrRouter(sink: LogSink): (line: string) => void {
  let current: Level = "error";
  return (line) => {
    if (line.trim() === "") {
      return;
    }
    current = levelOf(line) ?? current;
    sink[current](line);
  };
}

/** Feeds complete stderr lines of the server process to `sink`. */
export function pipeServerStderr(input: Readable, sink: LogSink): void {
  const route = createStderrRouter(sink);
  readline
    .createInterface({
      input,
      crlfDelay: Infinity,
      terminal: false,
      historySize: 0,
    })
    .on("line", route);
}
