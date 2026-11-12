// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { execFileSync, spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { closeSync, existsSync, ftruncateSync, lstatSync, mkdirSync, opendirSync, openSync, readdirSync, realpathSync, renameSync, writeFileSync, writeSync } from 'node:fs';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { readEvidenceText } from './read-evidence.mjs';
import { createProvisioningReport, digest, downloadPlatform, provisionWithBudget, provisioningLimits, validateProvisioningContext, validateProvisioningReport } from './vscode-provisioning-policy.mjs';
import { launchProvisioningAttempt } from './vscode-provisioning-process.mjs';
import { validateVscodeTestInputs, vscodeTestCliVersion, vscodeTestVersion } from '../../editors/code/tools/vscode-test-config.mjs';

const editorRoot = resolve(fileURLToPath(new URL('../../editors/code/', import.meta.url)));
const require = (condition, code) => { if (!condition) throw new Error(code); };
const json = path => JSON.parse(readEvidenceText(path, 64 * 1024));
const encode = value => JSON.stringify(value, null, 2) + '\n';
const writeNew = (path, value) => writeFileSync(path, encode(value), { flag: 'wx', mode: 0o600 });
const canonicalJson = path => {
  const bytes = readEvidenceText(path, 64 * 1024);
  const value = JSON.parse(bytes);
  require(bytes === encode(value), 'noncanonical-private-evidence');
  return value;
};
const git = args => execFileSync('git', args, { cwd: editorRoot, encoding: 'utf8', timeout: 10000, maxBuffer: 4096 }).trim();
const inside = (parent, child) => { const suffix = relative(parent, child); return suffix !== '' && suffix !== '..' && !suffix.startsWith('..' + sep) && !isAbsolute(suffix); };

export function provisioningContext(invocationId) {
  const testedSha = git(['rev-parse', 'HEAD']);
  require(process.env.GITHUB_ACTIONS === 'true' && testedSha === process.env.GITHUB_SHA, 'invalid-checkout-context');
  const event = JSON.parse(readEvidenceText(process.env.GITHUB_EVENT_PATH, 4 * 1024 * 1024));
  require(/^[1-9][0-9]*$/.test(process.env.GITHUB_RUN_ATTEMPT ?? ''), 'invalid-context');
  const context = {
    repository: process.env.GITHUB_REPOSITORY, headSha: event.pull_request?.head?.sha ?? testedSha, testedSha,
    runId: process.env.GITHUB_RUN_ID, runAttempt: Number(process.env.GITHUB_RUN_ATTEMPT), workflow: process.env.GITHUB_WORKFLOW, jobKey: process.env.GITHUB_JOB,
    matrix: process.env.GVY_VSCODE_MATRIX, runnerOS: process.env.RUNNER_OS, runnerArch: process.env.RUNNER_ARCH, invocationId,
  };
  validateProvisioningContext(context);
  const host = { darwin: 'macOS', linux: 'Linux', win32: 'Windows' }[process.platform];
  require(context.runnerOS === host && context.runnerArch === (process.arch === 'arm64' ? 'ARM64' : 'X64'), 'invalid-host-context');
  return context;
}

function roots(reportRoot, invocationId, create = false) {
  require(isAbsolute(process.env.RUNNER_TEMP ?? '') && isAbsolute(reportRoot), 'invalid-report-root');
  const temp = realpathSync(process.env.RUNNER_TEMP);
  const publicRoot = resolve(reportRoot);
  const privateBase = join(temp, 'gvy-vscode-private');
  require(inside(temp, publicRoot) && !inside(realpathSync(editorRoot), publicRoot) && publicRoot !== privateBase && !inside(privateBase, publicRoot) && !inside(publicRoot, privateBase), 'invalid-report-root');
  if (create) {
    mkdirSync(publicRoot, { mode: 0o700 });
    try { mkdirSync(privateBase, { mode: 0o700 }); } catch (error) { if (error.code !== 'EEXIST') throw error; }
  }
  for (const path of [publicRoot, privateBase]) {
    const info = lstatSync(path);
    require(info.isDirectory() && !info.isSymbolicLink() && realpathSync(path) === path, 'invalid-report-root');
  }
  const privateRoot = join(privateBase, invocationId);
  if (create) mkdirSync(privateRoot, { mode: 0o700 });
  const info = lstatSync(privateRoot);
  require(info.isDirectory() && !info.isSymbolicLink() && realpathSync(privateRoot) === privateRoot, 'invalid-private-root');
  return { publicRoot, privateRoot };
}

function readInitial(reportRoot) {
  const bytes = readEvidenceText(join(reportRoot, 'provisioning.json'), 64 * 1024);
  const report = JSON.parse(bytes);
  const context = provisioningContext(report.context?.invocationId);
  validateProvisioningReport(report, context);
  require(bytes === encode(report) && report.integration.state === 'unexecuted', 'noncanonical-provisioning-evidence');
  const paths = roots(reportRoot, context.invocationId);
  const state = canonicalJson(join(paths.privateRoot, 'state.json'));
  require(isDeepStrictEqual(Object.keys(state).sort(), ['context', 'executable', 'receiptSha256']) &&
    isDeepStrictEqual(state.context, context) && state.receiptSha256 === digest(bytes) &&
    (report.provisioning.ok ? typeof state.executable === 'string' : state.executable === null), 'stale-private-evidence');
  return { report, context, state, ...paths };
}

export async function provisionEditor(reportRoot) {
  require(!process.env.VSCODE_TEST_EXECUTABLE, 'unexpected-editor-override');
  const context = provisioningContext(randomUUID());
  const paths = roots(reportRoot, context.invocationId, true);
  let executable = null;
  let result;
  try {
    result = await provisionWithBudget(context, async ({ number, budgetMs }) => {
      const cachePath = join(paths.privateRoot, `cache-${number}`);
      require(!existsSync(cachePath), 'cache-must-be-fresh');
      const observed = await launchProvisioningAttempt({ context, number, budgetMs, editorRoot, cachePath, platform: downloadPlatform(context), token: randomUUID() }, { logDir: join(paths.privateRoot, `attempt-${number}`) });
      if (observed.result.outcome === 'success') {
        try {
          const path = realpathSync(observed.executable);
          require(inside(realpathSync(cachePath), path) && lstatSync(path).isFile(), 'invalid-delivered-path');
          executable = path;
        } catch {
          observed.result.outcome = 'verification-error'; observed.result.delivery = null;
          observed.result.error = { code: 'verification-error', messageSha256: null };
        }
      }
      return observed.result;
    });
  } catch {
    result = { ok: false, attempts: [], elapsedMs: 0, error: 'invalid-evidence' };
  }
  const report = createProvisioningReport(context, result);
  const text = encode(report);
  writeFileSync(join(paths.publicRoot, 'provisioning.json'), text, { flag: 'wx', mode: 0o600 });
  writeNew(join(paths.privateRoot, 'state.json'), { context, executable, receiptSha256: digest(text) });
  console.log(`VS Code provisioning ${result.ok ? 'passed' : `failed (${result.error})`}: requested ${vscodeTestVersion}, ${result.attempts.length} attempt(s); assertions unexecuted.`);
  return result.ok;
}

function validateInstalledPath(state, privateRoot) {
  require(typeof state.executable === 'string' && isAbsolute(state.executable) && !/[\0\r\n]/.test(state.executable), 'invalid-delivered-path');
  const path = realpathSync(state.executable);
  require(path === state.executable && inside(privateRoot, path) && lstatSync(path).isFile(), 'invalid-delivered-path');
  return path;
}

export async function invokeEditorTests(reportRoot) {
  const data = readInitial(reportRoot);
  require(data.report.provisioning.ok, 'provisioning-incomplete');
  const executable = validateInstalledPath(data.state, data.privateRoot);
  const requireEditor = createRequire(join(editorRoot, 'package.json'));
  const entry = requireEditor.resolve('@vscode/test-cli');
  const packageRoot = resolve(dirname(entry), '..');
  const manifest = json(join(packageRoot, 'package.json'));
  require(manifest.name === '@vscode/test-cli' && manifest.version === vscodeTestCliVersion && manifest.bin?.['vscode-test'] === './out/bin.mjs', 'unsupported-test-cli');
  process.env.VSCODE_TEST_EXECUTABLE = executable;
  const configurations = (await import(pathToFileURL(join(editorRoot, '.vscode-test.mjs')))).default;
  validateVscodeTestInputs(json(join(editorRoot, 'package.json')), configurations, executable);
  const targetRoot = process.env.GVY_TEST_EXTENSION_ROOT;
  require((data.context.jobKey === 'package') === Boolean(targetRoot), 'unsupported-extension-root');
  if (targetRoot) validateVscodeTestInputs(json(join(targetRoot, 'package.json')), configurations, executable);
  return invokeIntegrationOnce(data.context, join(data.privateRoot, 'integration.json'), join(packageRoot, 'out', 'bin.mjs'), { ...process.env, VSCODE_TEST_EXECUTABLE: executable });
}

export async function invokeIntegrationOnce(context, markerPath, cli, env, spawnChild = spawn, editorLogsRoot = join(editorRoot, '.vscode-test', 'user-data', 'logs')) {
  validateProvisioningContext(context);
  const args = [cli, '--config', join(editorRoot, '.vscode-test.mjs')];
  if (context.jobKey === 'package') args.push('--grep', 'Implementation navigation', '--fail-zero', '--forbid-pending');
  require(!existsSync(markerPath), 'integration-already-invoked');
  const logsPrepared = prepareEditorLogs(editorLogsRoot, dirname(markerPath));
  // This marker proves invocation of the integration runner, not editor launch.
  const marker = { context, state: 'invoked', outcome: 'pending', exitCode: null, logsPrepared };
  const markerDescriptor = openSync(markerPath, 'wx', 0o600);
  try {
    writeSync(markerDescriptor, encode(marker));
    if (process.env.GITHUB_OUTPUT) writeFileSync(process.env.GITHUB_OUTPUT, 'invoked=true\n', { flag: 'a' });
    let status = { code: 127, signal: null }, spawnFailed = false;
    try {
      const child = spawnChild(process.execPath, args, { cwd: editorRoot, env, stdio: 'inherit', windowsHide: true, shell: false });
      child.on('error', () => { spawnFailed = true; });
      const interrupt = () => child.kill('SIGINT'), terminate = () => child.kill('SIGTERM');
      process.once('SIGINT', interrupt); process.once('SIGTERM', terminate);
      try { status = await new Promise(resolve => child.once('close', (code, signal) => resolve({ code, signal }))); }
      finally { process.off('SIGINT', interrupt); process.off('SIGTERM', terminate); }
    } catch { spawnFailed = true; }
    marker.outcome = !spawnFailed && status.code === 0 && !status.signal ? 'success' : 'failure';
    marker.exitCode = spawnFailed ? 127 : status.code ?? 1;
    ftruncateSync(markerDescriptor, 0);
    writeSync(markerDescriptor, encode(marker), 0);
  } finally { closeSync(markerDescriptor); }
  return marker.outcome === 'success';
}

export function countEditorLogs(root) {
  const path = resolve(root);
  require(root === path, 'noncanonical-editor-log-root');
  const parent = dirname(path);
  require(lstatSync(parent).isDirectory() && !lstatSync(parent).isSymbolicLink() && realpathSync(parent) === parent, 'linked-editor-log-parent');
  if (!existsSync(root)) return 0;
  const rootInfo = lstatSync(root);
  require(rootInfo.isDirectory() && !rootInfo.isSymbolicLink() && realpathSync(root) === root, 'invalid-editor-log-root');
  const pending = [root]; let entries = 0; let files = 0;
  while (pending.length) {
    const path = pending.pop();
    const info = lstatSync(path);
    require(!info.isSymbolicLink(), 'linked-editor-logs');
    if (info.isDirectory()) {
      const directory = opendirSync(path);
      try {
        let entry;
        while ((entry = directory.readSync()) !== null) {
          require(++entries <= 5000, 'editor-log-inventory-limit'); pending.push(join(path, entry.name));
        }
      } finally { directory.closeSync(); }
    }
    else { require(info.isFile(), 'nonregular-editor-log'); files++; }
  }
  return files;
}

function ensureDirectory(path) {
  if (!existsSync(path)) { ensureDirectory(dirname(path)); mkdirSync(path, { mode: 0o700 }); }
  require(lstatSync(path).isDirectory() && !lstatSync(path).isSymbolicLink() && realpathSync(path) === path, 'linked-editor-log-parent');
}

export function prepareEditorLogs(root, privateRoot) {
  const path = resolve(root);
  ensureDirectory(dirname(path));
  require(realpathSync(privateRoot) === privateRoot && lstatSync(privateRoot).isDirectory(), 'invalid-private-root');
  let priorFiles = 0;
  if (existsSync(path)) {
    require(lstatSync(path).isDirectory() && !lstatSync(path).isSymbolicLink() && realpathSync(path) === path, 'linked-editor-logs');
    priorFiles = countEditorLogs(path);
    const previous = join(privateRoot, 'previous-editor-logs');
    require(!existsSync(previous), 'reused-editor-log-quarantine');
    // Preserve the original bytes privately without copying an unbounded tree.
    // Cross-volume, permission or rename failures stop before runner invocation.
    renameSync(path, previous);
  }
  mkdirSync(path, { mode: 0o700 });
  require(countEditorLogs(path) === 0, 'nonempty-editor-log-start');
  return { fresh: true, priorFiles };
}

export function auditEditorProvisioning(reportRoot, editorLogsRoot = join(editorRoot, '.vscode-test', 'user-data', 'logs')) {
  require(isDeepStrictEqual(readdirSync(reportRoot).sort(), ['provisioning.json']), 'unexpected-public-evidence-file');
  const data = readInitial(reportRoot);
  const markerPath = join(data.privateRoot, 'integration.json');
  if (existsSync(markerPath)) {
    const marker = canonicalJson(markerPath);
    require(isDeepStrictEqual(Object.keys(marker).sort(), ['context', 'exitCode', 'logsPrepared', 'outcome', 'state']) && isDeepStrictEqual(marker.context, data.context) &&
      marker.state === 'invoked' && ['success', 'failure'].includes(marker.outcome) && Number.isInteger(marker.exitCode) && marker.exitCode >= 0 && marker.exitCode <= 255 &&
      (marker.outcome === 'success') === (marker.exitCode === 0), 'invalid-integration-marker');
    require(marker.logsPrepared && isDeepStrictEqual(Object.keys(marker.logsPrepared).sort(), ['fresh', 'priorFiles']) && marker.logsPrepared.fresh === true &&
      Number.isInteger(marker.logsPrepared.priorFiles) && marker.logsPrepared.priorFiles >= 0 && marker.logsPrepared.priorFiles <= 5000, 'invalid-editor-log-preparation');
    require(data.report.provisioning.ok, 'integration-before-provisioning');
    validateInstalledPath(data.state, data.privateRoot);
    const files = countEditorLogs(editorLogsRoot);
    data.report.integration = { state: 'invoked', outcome: marker.outcome, editorLogs: { ...marker.logsPrepared, present: files > 0, files } };
  }
  data.report.ok = data.report.provisioning.ok && data.report.integration.outcome === 'success' && data.report.integration.editorLogs.present;
  validateProvisioningReport(data.report, data.context);
  writeNew(join(reportRoot, 'result.json'), data.report);
  // The upload names only this audited projection, never this directory or raw logs.
  if (process.env.GITHUB_OUTPUT) {
    const logsValid = data.report.integration.state === 'invoked' && data.report.integration.editorLogs.present;
    writeFileSync(process.env.GITHUB_OUTPUT, `safe=true\n${logsValid ? 'editor_logs_valid=true\n' : ''}`, { flag: 'a' });
  }
  const phase = data.report.integration.state === 'unexecuted' ? 'integration unexecuted; editor logs not expected' :
    data.report.integration.editorLogs.present ? 'integration invoked; editor logs present' : 'integration invoked; required editor logs missing';
  console.log(`VS Code evidence ${data.report.ok ? 'passed' : 'failed'}: ${phase}.`);
  return data.report.ok;
}

async function main(args) {
  require(args.length === 3 && ['--provision', '--run', '--audit'].includes(args[0]) && args[1] === '--report', 'Usage: provision-vscode.mjs --provision|--run|--audit --report DIR');
  const ok = args[0] === '--provision' ? await provisionEditor(resolve(args[2])) : args[0] === '--run' ? await invokeEditorTests(resolve(args[2])) : auditEditorProvisioning(resolve(args[2]));
  process.exitCode = ok ? 0 : 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { await main(process.argv.slice(2)); }
  catch (error) { console.error(`VS Code provisioning/evidence failed: ${/^[a-z][a-z-]{1,80}$/.test(error.message) ? error.message : 'invalid-provisioning-evidence'}`); process.exitCode = 1; }
}
