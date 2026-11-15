// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { spawn } from "node:child_process";
import { openSync, writeSync, closeSync } from "node:fs";
import { delay, MAX_LOG_BYTES, readJson } from "./io.mjs";

/** strace's existing stderr is the raw trace stream. Never reopen Node's socketpair via /proc/fd. */
export async function captureTrace(
  argv,
  { tracePath, resultPath, deadlineMs, maxBytes = MAX_LOG_BYTES, afterResult },
) {
  const trace = openSync(tracePath, "wx", 0o600);
  let diagnostics;
  let child;
  try {
    diagnostics = openSync(`${tracePath}.stdout`, "wx", 0o600);
    child = spawn(argv[0], argv.slice(1), {
      stdio: ["ignore", "pipe", "pipe"],
    });
  } catch (error) {
    closeSync(trace);
    if (diagnostics !== undefined) closeSync(diagnostics);
    throw error;
  }
  let exited = false;
  let closed = false;
  let failure;
  let bytes = 0;
  const traceChunks = [];
  const termination = new Promise((resolve) =>
    child.once("close", (code, signal) => {
      closed = true;
      resolve({ code, signal });
    }),
  );
  const drain = async () => {
    let timeout;
    try {
      await Promise.race([
        termination,
        new Promise((resolve) => {
          timeout = setTimeout(resolve, 1000);
        }),
      ]);
    } finally {
      clearTimeout(timeout);
    }
  };
  child.once("exit", () => {
    exited = true;
  });
  child.once("error", (error) => {
    failure ??= error;
    exited = true;
  });
  let deadlineReached;
  const deadline = new Promise((resolve) => {
    deadlineReached = resolve;
  });
  const timer = setTimeout(() => {
    failure ??= new Error("Trace deadline exceeded");
    child.kill("SIGKILL");
    deadlineReached();
  }, deadlineMs);
  for (const [stream, fd] of [
    [child.stderr, trace],
    [child.stdout, diagnostics],
  ]) {
    stream.on("data", (chunk) => {
      bytes += chunk.length;
      if (bytes > maxBytes) {
        failure ??= new Error("Trace/output byte limit exceeded");
        child.kill("SIGKILL");
      } else {
        try {
          let offset = 0;
          while (offset < chunk.length) {
            const written = writeSync(fd, chunk, offset, chunk.length - offset);
            if (written === 0)
              throw new Error("Trace descriptor write made no progress");
            offset += written;
          }
          if (fd === trace) traceChunks.push(chunk);
        } catch (error) {
          failure ??= error;
          child.kill("SIGKILL");
        }
      }
    });
  }
  let result;
  try {
    while (!result && !failure) {
      try {
        result = await readJson(resultPath);
      } catch (error) {
        if (error.code !== "ENOENT") throw error;
      }
      if (!result && exited) {
        await drain();
        const raw = Buffer.concat(traceChunks).subarray(0, 2048).toString();
        throw new Error(`Traced command ended without its result: ${raw}`);
      }
      if (!result) await delay(25);
    }
    if (failure) throw failure;
    // A result is only a teardown request. The main worker and every descendant must
    // stop while the observer is still attached; never detach a successful capture.
    if (afterResult)
      await afterResult(result, { pid: child.pid, isRunning: () => !exited });
    const outcome = await Promise.race([termination, deadline]);
    if (failure) throw failure;
    if (outcome.code !== 0 || outcome.signal !== null) {
      throw new Error(
        `Observer did not exit naturally with code 0: ${JSON.stringify(outcome)}`,
      );
    }
  } finally {
    // This kill is failure cleanup only. Success already observed natural exit and EOF.
    if (!exited) child.kill("SIGKILL");
    await drain();
    if (!closed) {
      failure ??= new Error(
        "Trace descriptors did not drain after process exit",
      );
      child.kill("SIGKILL");
      child.stdout.destroy();
      child.stderr.destroy();
    }
    clearTimeout(timer);
    closeSync(trace);
    closeSync(diagnostics);
  }
  if (failure) throw failure;
  return { result, rawTrace: Buffer.concat(traceChunks).toString("utf8") };
}
