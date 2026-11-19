// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { spawn } from "node:child_process";
import { writeFile } from "node:fs/promises";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { isDeepStrictEqual } from "node:util";
import { FrameParser, RpcPeer } from "../logging/lsp-protocol.mjs";
import { command, readJson, writeJson, MAX_LOG_BYTES } from "./io.mjs";
import { readProcessIdentity } from "./processes.mjs";

function selected(value, pointer) {
  if (pointer === "") return value;
  if (!pointer.startsWith("/")) throw new Error("Expected JSON pointer");
  return pointer
    .slice(1)
    .split("/")
    .reduce(
      (current, key) =>
        current?.[key.replaceAll("~1", "/").replaceAll("~0", "~")],
      value,
    );
}

async function lspScenario(scenario, manifest, directory, env) {
  const child = spawn(
    join(manifest.javaHome, "bin/java"),
    ["-Xmx512m", "-jar", manifest.artifacts.serverJar.path],
    { cwd: scenario.cwd, env, stdio: ["pipe", "pipe", "pipe"] },
  );
  const exited = new Promise((resolve) =>
    child.once("exit", (code, signal) => resolve({ code, signal })),
  );
  const notifications = [];
  let notificationBytes = 0;
  const waiters = new Set();
  let failure;
  const stderr = [];
  let stderrBytes = 0;
  const fatal = (error) => {
    failure ??= error;
    peer.close(error);
    child.kill("SIGKILL");
  };
  const peer = new RpcPeer(child.stdin, {
    deadlineMs: 10000,
    configuration: scenario.configuration ?? {},
    onFatal: fatal,
    onNotification: (notification) => {
      notificationBytes += Buffer.byteLength(JSON.stringify(notification));
      if (notificationBytes > 1024 * 1024 || notifications.length >= 1000) {
        fatal(new Error("LSP notification history limit exceeded"));
        return;
      }
      notifications.push(notification);
      for (const waiter of waiters) waiter();
    },
  });
  const parser = new FrameParser((message) => peer.receive(message));
  child.stdout.on("data", (chunk) => {
    try {
      parser.write(chunk);
    } catch (error) {
      fatal(error);
    }
  });
  child.stderr.on("data", (chunk) => {
    stderrBytes += chunk.length;
    if (stderrBytes > MAX_LOG_BYTES)
      fatal(new Error("LSP stderr limit exceeded"));
    else stderr.push(chunk);
  });
  child.once("error", fatal);
  child.once("exit", () => peer.close(new Error("LSP process exited")));
  const observations = [];
  try {
    const rootUri = pathToFileURL(scenario.cwd).href;
    const initialized = await peer.request("initialize", {
      processId: process.pid,
      rootUri,
      workspaceFolders: [{ uri: rootUri, name: "qualification" }],
      capabilities: {},
      initializationOptions: scenario.initializationOptions,
    });
    if (!initialized || typeof initialized.capabilities !== "object")
      throw new Error("Real candidate returned no initialize capabilities");
    if (
      Buffer.byteLength(JSON.stringify(initialized.serverInfo ?? null)) > 8192
    )
      throw new Error("LSP serverInfo limit exceeded");
    observations.push({
      method: "initialize",
      serverInfo: initialized.serverInfo ?? null,
    });
    peer.notify("initialized", {});
    for (const step of scenario.steps) {
      if (step.kind === "notify") {
        peer.notify(step.method, step.params);
        continue;
      }
      let observed;
      if (step.kind === "request")
        observed = await peer.request(step.method, step.params);
      else
        observed = await new Promise((resolve, reject) => {
          const timer = setTimeout(() => {
            waiters.delete(check);
            reject(new Error(`Notification timed out: ${step.method}`));
          }, 10000);
          const check = () => {
            const found = notifications.find(
              (message) =>
                message.method === step.method &&
                isDeepStrictEqual(
                  selected(message.params, step.pointer),
                  step.equals,
                ),
            );
            if (found) {
              clearTimeout(timer);
              waiters.delete(check);
              resolve(found.params);
            }
          };
          waiters.add(check);
          check();
        });
      if (!isDeepStrictEqual(selected(observed, step.pointer), step.equals))
        throw new Error(`LSP assertion failed: ${step.method} ${step.pointer}`);
      observations.push({
        method: step.method,
        pointer: step.pointer,
        passed: true,
      });
    }
    await peer.request("shutdown", null);
    peer.notify("exit");
    const timeout = setTimeout(
      () => fatal(new Error("LSP did not exit after shutdown")),
      5000,
    );
    const outcome = await exited;
    clearTimeout(timeout);
    if (failure) throw failure;
    if (outcome.code !== 0)
      throw new Error(`LSP exit was ${JSON.stringify(outcome)}`);
    return observations;
  } finally {
    peer.close();
    child.kill("SIGKILL");
    await writeFile(
      join(directory, `${scenario.id}.stderr`),
      Buffer.concat(stderr),
      { mode: 0o600 },
    );
  }
}

const [manifestPath, directory, fixturesPath] = process.argv.slice(2);
const manifest = await readJson(manifestPath);
const fixtures = await readJson(fixturesPath);
const scenarios = [];
const worker = await readProcessIdentity(process.pid);
const env = {
  ...process.env,
  JAVA_HOME: manifest.javaHome,
  GVY_QUALIFICATION_SERVER_JAR: manifest.artifacts.serverJar.path,
  GVY_QUALIFICATION_VSIX: manifest.artifacts.extensionVsix.path,
  GVY_QUALIFICATION_GRADLE: manifest.gradle.installation,
  GVY_QUALIFICATION_HTTP_ALLOWED: fixtures.origins.allowed,
  GVY_QUALIFICATION_HTTP_SECOND: fixtures.origins.second,
  GVY_QUALIFICATION_HTTP_DENIED: fixtures.origins.denied,
  GVY_QUALIFICATION_HTTP_AUTHORIZATION: fixtures.authorization,
};
try {
  for (const scenario of manifest.scenarios) {
    const result = { id: scenario.id, kind: scenario.kind, passed: false };
    scenarios.push(result);
    try {
      if (scenario.kind === "lsp")
        result.observations = await lspScenario(
          scenario,
          manifest,
          directory,
          env,
        );
      else {
        const outcome = await command(scenario.argv, {
          cwd: scenario.cwd,
          env,
          timeoutMs: manifest.deadlineSeconds * 1000,
        });
        await writeFile(
          join(directory, `${scenario.id}.stdout`),
          outcome.stdout,
          { mode: 0o600 },
        );
        await writeFile(
          join(directory, `${scenario.id}.stderr`),
          outcome.stderr,
          { mode: 0o600 },
        );
        result.exitCode = outcome.code;
        if (outcome.code !== 0)
          throw new Error(
            `Command exited ${outcome.code}, signal ${outcome.signal}`,
          );
        result.semanticQualification =
          "Requires separately reviewed real-candidate command assertions";
      }
      result.passed = true;
    } catch (error) {
      result.error = error.message;
      break;
    }
  }
} finally {
  await writeJson(join(directory, "worker-result.json"), { worker, scenarios });
}
// Daemons are killed when the owning PID namespace init exits, including setsid descendants.
process.exit(scenarios.every((scenario) => scenario.passed) ? 0 : 1);
