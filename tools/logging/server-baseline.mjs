// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

/** Real-server measurement. This tool never records source, raw logs or LSP error text. */
import { createHash } from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import { accessSync, constants, createReadStream, mkdtempSync, readFileSync, realpathSync, rmSync, statSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { performance } from 'node:perf_hooks';
import { FrameParser, PhysicalLineCounter, RpcPeer } from './lsp-protocol.mjs';
import { sourceFixture } from './fixtures.mjs';

const hash = (data) => createHash('sha256').update(data).digest('hex');
const until = (promise, ms) => new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error('deadline')), ms);
  promise.then((value) => { clearTimeout(timer); resolve(value); }, (error) => { clearTimeout(timer); reject(error); });
});
const quantile = (values, q) => values.length ? [...values].sort((a, b) => a - b)[Math.ceil(q * values.length) - 1] : null;

function workspaceConfiguration(level) {
  return { 'groovy.server.logLevel': level, groovy: { server: { logLevel: level } } };
}

function initializeParams(rootUri, level) {
  return { processId: process.pid, rootUri, capabilities: { workspace: { configuration: true } }, initializationOptions: { 'groovy.server.logLevel': level } };
}

function validHover(value) {
  if (value === null) return true;
  if (!value || typeof value !== 'object' || Array.isArray(value) || !Object.hasOwn(value, 'contents')) return false;
  const content = (item) => typeof item === 'string' || (item && typeof item === 'object' && !Array.isArray(item) && typeof item.value === 'string' && (item.kind === 'markdown' || item.kind === 'plaintext' || typeof item.language === 'string'));
  return Array.isArray(value.contents) ? value.contents.every(content) : content(value.contents);
}
function validCompletion(value) {
  if (value === null) return true;
  if (!Array.isArray(value) && (!value || typeof value !== 'object' || typeof value.isIncomplete !== 'boolean')) return false;
  const items = Array.isArray(value) ? value : value.items;
  return Array.isArray(items) && items.every((item) => item && typeof item === 'object' && !Array.isArray(item) && typeof item.label === 'string');
}

function hoverKind(value) {
  if (value === null) return { kind: 'returned-no-result', count: 0 };
  const contents = Array.isArray(value.contents) ? value.contents : [value.contents];
  const nonempty = contents.filter((entry) => typeof entry === 'string' ? entry.length > 0 : entry.value.length > 0).length;
  return { kind: nonempty ? 'content-present' : 'empty-content', count: nonempty };
}
function completionKind(value) {
  if (value === null) return { kind: 'returned-no-result', count: 0 };
  const items = Array.isArray(value) ? value : value.items;
  return { kind: items.length ? 'items-present' : 'empty-list', count: items.length };
}
/** Write sensitive local launch provenance once without exposing it in the receipt. */
export function writePrivateProfile(file, javaExecutable, javaVersion, javaBanner) {
  writeFileSync(file, JSON.stringify({ javaExecutable, javaVersion, javaBanner }, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
}
/** Return only an allowlisted vendor and bounded version token. */
export function parseJavaVersion(banner) {
  const match = /^(openjdk|java) version "([0-9][A-Za-z0-9._+\-]{0,63})"/m.exec(banner);
  if (!match) throw new Error('Java version unrecognized');
  return { vendor: match[1], version: match[2] };
}

function resolveJava() {
  const name = process.platform === 'win32' ? 'java.exe' : 'java';
  for (const directory of (process.env.PATH ?? '').split(path.delimiter)) {
    if (!directory) continue;
    const candidate = path.join(directory, name);
    try { accessSync(candidate, constants.X_OK); return realpathSync(candidate); } catch { /* try next */ }
  }
  throw new Error('Java executable unavailable');
}

/** Run against an already-created child; caller supplies only a synthetic URI and text. */
export async function runSession(child, { uri, text, level, warmups, samples, rootUri = null, deadlineMs = 15000, spawnStartMs = performance.now() }) {
  const stderr = new PhysicalLineCounter();
  const firstPublications = new Map();
  const starts = new Map();
  const runs = [];
  const configuration = workspaceConfiguration(level);
  let terminal = 'not-probed';
  let closed = false;
  let closeCode = null;
  let closeSignal = null;
  let streamError = false;
  let stdoutEnded = false;
  let stderrEnded = false;
  let collecting = true;
  let initialization = null;
  let peer;
  let resolveClose;
  const closePromise = new Promise((resolve) => { resolveClose = resolve; });
  peer = new RpcPeer(child.stdin, {
    deadlineMs,
    configuration,
    onFatal(error) { failStream(error); },
    onNotification(message) {
      if (message.method !== 'textDocument/publishDiagnostics') return;
      const params = message.params;
      if (params?.uri !== uri || !Number.isInteger(params.version) || !starts.has(params.version) || firstPublications.has(params.version)) return;
      if (!Array.isArray(params.diagnostics)) return;
      firstPublications.set(params.version, { elapsedMs: performance.now() - starts.get(params.version), diagnosticCount: params.diagnostics.length });
    },
  });
  const parser = new FrameParser((message) => peer.receive(message));
  const failStream = (error) => { streamError = true; peer.close(error?.kind === 'output-saturated' ? error : new Error('stream failure')); };
  child.stdout.on('data', (chunk) => { if (streamError) return; try { parser.write(chunk); } catch (error) { failStream(error); } });
  child.stdout.on('end', () => { stdoutEnded = true; try { parser.end(); } catch { failStream(); } peer.close(new Error('stdout EOF')); });
  child.stdout.on('close', () => { if (!stdoutEnded) failStream(); });
  child.stdout.on('error', failStream);
  child.stderr.on('data', (chunk) => { if (collecting) stderr.write(chunk); });
  child.stderr.on('end', () => { stderrEnded = true; });
  child.stderr.on('close', () => { if (!stderrEnded) failStream(); });
  child.stderr.on('error', failStream);
  child.stdin.on('error', failStream);
  child.on('error', failStream);
  child.on('close', (code, signal) => { closed = true; closeCode = Number.isInteger(code) && code >= 0 && code <= 255 ? code : null; closeSignal = ['SIGTERM', 'SIGKILL', 'SIGABRT', 'SIGINT'].includes(signal) ? signal : null; peer.close(new Error('child closed')); resolveClose(); });
  let status = 'incomplete';
  let failureStage = 'initialize';
  let failureClass = null;
  let failureCode = null;
  let shutdownResultType = null;
  let shutdownConformance = null;
  const stage = async (method, params, timing) => {
    failureStage = method;
    const result = await peer.request(method, params, timing);
    if (timing && timing.responseMs < timing.enqueueMs) throw new Error('invalid monotonic span');
    return result;
  };
  const sendChange = (version, source) => {
    starts.set(version, performance.now());
    peer.notify('textDocument/didChange', { textDocument: { uri, version }, contentChanges: [{ text: source }] });
  };
  try {
    initialization = {};
    const init = await stage('initialize', initializeParams(rootUri, level), initialization);
    if (!init || typeof init !== 'object' || !init.capabilities || typeof init.capabilities !== 'object') throw new Error('invalid initialization');
    peer.notify('initialized', {});
    starts.set(1, performance.now());
    peer.notify('textDocument/didOpen', { textDocument: { uri, languageId: 'groovy', version: 1, text } });
    for (let i = 0; i < warmups + samples; i++) {
      const version = i + 2;
      const sample = { iteration: i, phase: i < warmups ? 'warmup' : 'measured', status: 'incomplete', version, changeEnqueueMs: null, hover: {}, completion: {}, terminal: null };
      runs.push(sample);
      sendChange(version, `${text}\n// baseline iteration ${i}\n`);
      sample.changeEnqueueMs = starts.get(version);
      const position = { line: 0, character: Math.min(6, text.split('\n')[0].length) };
      const hover = await stage('textDocument/hover', { textDocument: { uri }, position }, sample.hover);
      if (!validHover(hover)) throw new Error('invalid hover');
      Object.assign(sample.hover, hoverKind(hover));
      const completion = await stage('textDocument/completion', { textDocument: { uri }, position }, sample.completion);
      if (!validCompletion(completion)) throw new Error('invalid completion');
      Object.assign(sample.completion, completionKind(completion));
      if (terminal !== 'unavailable') {
        const timing = {};
        try {
          const result = await stage('groovy/awaitDiagnostics', { uri, version }, timing);
          if (!result || result.uri !== uri || (result.version !== version && result.version !== null) || !Number.isSafeInteger(result.runId) || !Number.isSafeInteger(result.configurationGeneration) || result.complete !== true || !Array.isArray(result.diagnostics) || !Array.isArray(result.providerIds) || !result.providerIds.every((id) => typeof id === 'string') || !Array.isArray(result.failures)) throw new Error('invalid terminal diagnostics');
          if (result.version === null) { sample.status = 'unversioned'; throw new Error('unversioned terminal result'); }
          terminal = result.failures.length ? 'complete-with-provider-failures' : 'available';
          sample.terminal = { ...timing, failureCount: result.failures.length, diagnosticCount: result.diagnostics.length, status: result.failures.length ? 'provider-failures' : 'complete' };
        } catch (error) {
          if (error.code === -32601) terminal = 'unavailable';
          else { if (error.code === -32801) sample.status = 'obsolete'; throw error; }
        }
      }
      sample.status = sample.terminal?.failureCount ? 'provider-failures' : 'complete';
    }
    failureStage = 'shutdown';
    const shutdown = await stage('shutdown', null);
    shutdownResultType = shutdown === null ? 'null' : Array.isArray(shutdown) ? 'array' : typeof shutdown;
    if (shutdown === null) shutdownConformance = 'standard-null';
    else if (shutdownResultType === 'object' && shutdown && Object.keys(shutdown).length === 0) shutdownConformance = 'nonstandard-empty-object';
    else throw new Error('invalid shutdown');
    peer.notify('exit', undefined);
    failureStage = 'child-close';
    await until(closePromise, deadlineMs);
    if (closeCode !== 0 || streamError || !stdoutEnded || !stderrEnded) throw new Error('unclean close');
    const providerFailures = runs.some((run) => run.status === 'provider-failures');
    status = providerFailures ? 'complete-with-provider-failures' : shutdownConformance === 'nonstandard-empty-object' ? 'complete-with-protocol-deviation' : 'complete';
  } catch (error) {
    // Keep only an allowlisted class and numeric protocol code, never error text.
    if (Number.isInteger(error?.code)) { failureClass = 'json-rpc-error'; failureCode = [-32601, -32602, -32801].includes(error.code) ? error.code : null; }
    else if (error?.kind === 'output-saturated') failureClass = 'output-saturated';
    else if (typeof error?.message === 'string' && error.message.endsWith(' timed out')) failureClass = 'timeout';
    else if (error?.message === 'stdout EOF' || error?.message === 'child closed') failureClass = 'eof';
    else if (error?.message === 'invalid shutdown') failureClass = 'unexpected-shutdown-result';
    else if (error?.message === 'deadline') failureClass = 'deadline';
    else failureClass = 'protocol-or-runtime';
  } finally {
    if (!closed) {
      try { child.kill('SIGTERM'); } catch { /* report unreaped */ }
      try { await until(closePromise, Math.min(deadlineMs, 1000)); } catch { /* escalate */ }
      if (!closed) { try { child.kill('SIGKILL'); } catch { /* report unreaped */ } try { await until(closePromise, Math.min(deadlineMs, 1000)); } catch { /* report unreaped */ } }
    }
    if (!closed || !stdoutEnded || !stderrEnded || streamError) status = 'incomplete';
    if (!closed) {
      child.stdin.destroy(); child.stdout.destroy(); child.stderr.destroy();
      if (typeof child.unref === 'function') child.unref();
    }
  }
  collecting = false;
  const measured = runs.filter((run) => run.phase === 'measured');
  for (const run of measured) run.firstPublication = firstPublications.get(run.version) ?? null;
  const groups = (field, kinds) => Object.fromEntries(kinds.map((kind) => {
    const values = measured.filter((run) => run.status === 'complete' && run[field].kind === kind && Number.isFinite(run[field].responseMs)).map((run) => run[field].responseMs - run[field].enqueueMs);
    return [kind, { n: values.length, p50: quantile(values, .5), p95: quantile(values, .95) }];
  }));
  return {
    status, failureStage: status === 'incomplete' ? failureStage : null, failureClass, failureCode, shutdownResultType, shutdownConformance, childReaped: closed, childExitCode: closeCode, childSignal: closeSignal,
    startup: initialization?.responseMs === undefined ? null : { spawnToInitializeResponseMs: initialization.responseMs - spawnStartMs, ...initialization },
    warmupsAttempted: runs.filter((run) => run.phase === 'warmup').length,
    warmupsDiscarded: runs.filter((run) => run.phase === 'warmup' && run.status === 'complete').length,
    samples: measured,
    diagnostics: { terminal, firstPublication: 'version-matched first publication only' },
    requestSpans: { method: 'nearest-rank; response minus enqueue in monotonic milliseconds', hover: groups('hover', ['returned-no-result', 'empty-content', 'content-present']), completion: groups('completion', ['returned-no-result', 'empty-list', 'items-present']) },
    stderr: stderr.finish(), javaWrites: null, javaFlushes: null, syscalls: null,
  };
}

async function fileSha256(file) {
  const digest = createHash('sha256');
  for await (const chunk of createReadStream(file)) digest.update(chunk);
  return digest.digest('hex');
}

/** Resolve the exact verified file before launching from another working directory. */
export async function resolveVerifiedJar(file, provenance) {
  const resolved = realpathSync(path.resolve(file));
  const jarHash = await fileSha256(resolved);
  if (typeof provenance.jarSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(provenance.jarSha256) || jarHash !== provenance.jarSha256 || statSync(resolved).size !== provenance.jarBytes) throw new Error('JAR provenance mismatch');
  return { path: resolved, sha256: jarHash };
}

/** Recheck without letting a disappearing file erase a censored receipt. */
export async function recheckJar(file, expected) {
  try {
    const sha256 = await fileSha256(file);
    return { sha256, matches: sha256 === expected };
  } catch {
    return { sha256: null, matches: false };
  }
}

async function main() {
  const args = process.argv.slice(2);
  const keys = ['--jar', '--provenance', '--launch-profile', '--level', '--size', '--warmups', '--samples'];
  const names = args.filter((_, index) => index % 2 === 0);
  if (args.length % 2 || names.some((name) => !keys.includes(name)) || new Set(names).size !== names.length) throw new Error('Invalid arguments');
  const opts = Object.fromEntries(Array.from({ length: args.length / 2 }, (_, i) => [args[i * 2], args[i * 2 + 1]]));
  if (!opts['--jar'] || !opts['--provenance'] || !opts['--launch-profile'] || !['info', 'debug', 'trace'].includes(opts['--level']) || !['small', 'large'].includes(opts['--size'] ?? 'small')) throw new Error('Invalid arguments');
  const warmups = Number(opts['--warmups'] ?? 2);
  const samples = Number(opts['--samples'] ?? 5);
  if (![warmups, samples].every(Number.isSafeInteger) || warmups < 0 || samples < 1 || warmups + samples > 100) throw new Error('Invalid iteration counts');
  const provenance = JSON.parse(readFileSync(opts['--provenance'], 'utf8'));
  const verifiedJar = await resolveVerifiedJar(opts['--jar'], provenance);
  const jarHash = verifiedJar.sha256;
  if (typeof provenance.source?.sha !== 'string' || !/^[a-f0-9]{40}$/.test(provenance.source.sha) || (provenance.source.tree !== undefined && (typeof provenance.source.tree !== 'string' || !/^[a-f0-9]{40}$/.test(provenance.source.tree)))) throw new Error('Source provenance mismatch');
  const ignoredJavaEnvironment = ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH'];
  const ignoredJavaEnvironmentPresent = Object.fromEntries(ignoredJavaEnvironment.map((key) => [key, process.env[key] !== undefined]));
  const javaEnvironment = { ...process.env };
  for (const key of ignoredJavaEnvironment) delete javaEnvironment[key];
  const javaExecutable = resolveJava();
  const version = spawnSync(javaExecutable, ['-Xmx64m', '-XX:MaxMetaspaceSize=128m', '-version'], { encoding: 'utf8', maxBuffer: 16384, timeout: 10000, env: javaEnvironment });
  if (version.status !== 0) throw new Error('Java version unavailable');
  const javaBanner = version.stderr + version.stdout;
  const java = parseJavaVersion(javaBanner);
  writePrivateProfile(opts['--launch-profile'], javaExecutable, java.version, javaBanner);
  const fixtureSize = opts['--size'] ?? 'small';
  const text = sourceFixture('groovy', fixtureSize);
  const workspace = mkdtempSync(path.join(os.tmpdir(), 'gvy-logging-baseline-'));
  let removeWorkspace = true;
  try {
    const file = path.join(workspace, 'Sample.groovy');
    writeFileSync(file, text);
    const rootUri = pathToFileURL(`${workspace}${path.sep}`).href;
    const javaArgs = ['-Xmx512m', '-XX:MaxMetaspaceSize=256m', '-jar', verifiedJar.path];
    const exactConfiguration = { initialization: initializeParams(rootUri, opts['--level']), workspaceConfiguration: workspaceConfiguration(opts['--level']), javaExecutable, javaArgs, warmups, samples, deadlineMs: 15000, ignoredJavaEnvironmentPresent };
    const spawnStartMs = performance.now();
    const child = spawn(javaExecutable, javaArgs, { cwd: workspace, env: javaEnvironment, stdio: ['pipe', 'pipe', 'pipe'] });
    const result = await runSession(child, { uri: pathToFileURL(file).href, rootUri, text, level: opts['--level'], warmups, samples, spawnStartMs });
    removeWorkspace = result.childReaped;
    const checkedJar = await recheckJar(verifiedJar.path, jarHash);
    const jarHashAfter = checkedJar.sha256;
    if (!checkedJar.matches) { result.status = 'incomplete'; result.failureStage = 'jar-changed-or-unavailable'; }
    console.log(JSON.stringify({ schema: 'gvy-logging-baseline-v1', jarSha256: jarHash, jarSha256After: jarHashAfter, declaredSourceSha: provenance.source.sha, declaredSourceTree: provenance.source.tree ?? null, driverSha256: await fileSha256(fileURLToPath(import.meta.url)), protocolSha256: await fileSha256(fileURLToPath(new URL('./lsp-protocol.mjs', import.meta.url))), fixtureSize, fixtureSha256: hash(text), configurationSha256: hash(JSON.stringify(exactConfiguration)), configurationHashScope: 'initialization, workspace configuration, JVM arguments, iteration counts, deadline and removed variable names; remaining environment inherited', plannedWarmups: warmups, plannedSamples: samples, deadlineMs: 15000, ignoredJavaEnvironmentPresent, workspaceRetained: !result.childReaped, requestedLevel: opts['--level'], effectiveLevel: 'unknown', javaVendor: java.vendor, javaVersion: java.version, nodeVersion: process.version, os: { platform: os.platform(), release: os.release(), arch: os.arch() }, ...result }, null, 2));
    if (result.status !== 'complete') process.exitCode = 1;
  } finally { if (removeWorkspace) rmSync(workspace, { recursive: true, force: true }); }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try { await main(); } catch { console.error('Baseline driver failed before measurement'); process.exitCode = 1; }
}
