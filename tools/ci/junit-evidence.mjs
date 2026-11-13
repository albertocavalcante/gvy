// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

/** Compare supplied reports. Matching does not attest source, execution or job completion.
 * One Python parser invocation; the existing Windows intake also invokes its native reader.
 */
import { isUtf8 } from 'node:buffer';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { realpathSync } from 'node:fs';
import { isAbsolute } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { readEvidenceBytes, readEvidenceText } from './read-evidence.mjs';

const INPUT_LIMIT = 32 * 1024 * 1024;
const OUTPUT_LIMIT = 512 * 1024;
const XML_LIMIT = 1024 * 1024;
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const fields = (value, keys) => value !== null && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).sort().join(',') === [...keys].sort().join(',');
const text = value => typeof value === 'string' && value.length > 0 && value.length <= 4096 && value.isWellFormed();
const outcomes = ['passed', 'failed', 'error', 'skipped'];
const reasons = ['ok', 'invalid_xml', 'unsafe_xml', 'limit_exceeded', 'invalid_report', 'invalid_expectation', 'case_mismatch'];

/** Match Python ensure_ascii=True, including DEL and each non-ASCII UTF-16 unit. */
export function expectedContractDigest(reportId, expected) {
  const ascii = JSON.stringify([reportId, expected]).replace(/[\u007f-\uffff]/g,
    unit => '\\u' + unit.charCodeAt(0).toString(16).padStart(4, '0'));
  return sha(Buffer.from(ascii));
}

export function prepareJunitBatch(plans) {
  if (!Array.isArray(plans) || plans.length < 1 || plans.length > 1024) throw new Error('invalid_plan');
  let xmlBytes = 0, expectedBytes = 0, expectedCases = 0;
  const identities = [];
  const reports = plans.map(plan => {
    if (!fields(plan, ['path', 'reportId', 'expected']) || !text(plan.path) || !text(plan.reportId)
      || !Array.isArray(plan.expected) || plan.expected.length < 1 || plan.expected.length > 10_000) throw new Error('invalid_plan');
    let characters = 0;
    const expected = plan.expected.map(row => {
      if (!Array.isArray(row) || row.length !== 4 || !text(row[0]) || !text(row[1])
        || !outcomes.includes(row[2]) || !Number.isSafeInteger(row[3]) || row[3] < 1 || row[3] > 10_000) throw new Error('invalid_plan');
      characters += row[0].length + row[1].length;
      expectedBytes += Buffer.byteLength(row[0]) + Buffer.byteLength(row[1]);
      expectedCases += row[3];
      return [...row];
    });
    expectedBytes += Buffer.byteLength(plan.reportId);
    if (characters > 524_288 || expectedBytes > 4 * 1024 * 1024 || expectedCases > 10_000) throw new Error('input_limit');
    // This is the existing descriptor-based, no-follow intake, not another XML reader.
    let bytes;
    try { bytes = readEvidenceBytes(plan.path, XML_LIMIT); }
    catch { throw new Error('report_read_failed'); }
    xmlBytes += bytes.length;
    if (xmlBytes > 16 * 1024 * 1024) throw new Error('input_limit');
    const counts = [0, 0, 0, 0];
    for (const row of expected) {
      counts[0] += row[3];
      if (row[2] !== 'passed') counts[outcomes.indexOf(row[2])] += row[3];
    }
    const distinct = new Set(expected.map(row => JSON.stringify(row.slice(0, 2))));
    identities.push({ artifactSha256: sha(bytes), expectedContractSha256: expectedContractDigest(plan.reportId, expected),
      counts, duplicateCaseRows: counts[0] - distinct.size });
    return { reportId: plan.reportId, xmlBase64: bytes.toString('base64'), expected };
  });
  const input = Buffer.from(JSON.stringify({ schemaVersion: 1, reports }));
  if (input.length > INPUT_LIMIT) throw new Error('input_limit');
  return { input, identities };
}

/** One trusted Python direct child only. No process-tree cleanup assertion is made. */
export function runJunitPython(input, { python, timeoutMs = 10_000, signal } = {}, spawnProcess = spawn) {
  if (!Buffer.isBuffer(input) || input.length > INPUT_LIMIT || typeof python !== 'string' || !isAbsolute(python)
    || !Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 60_000) throw new Error('invalid_parser_options');
  if (signal?.aborted) return Promise.resolve({ kind: 'aborted' });
  return new Promise(resolve => {
    let child, timer, hard, done = false, spawned = false, reason, outputBytes = 0, stderrBytes = 0, inputComplete = false;
    const chunks = [];
    const finish = result => {
      if (done) return;
      done = true; clearTimeout(timer); clearTimeout(hard); signal?.removeEventListener('abort', abort);
      resolve(result);
    };
    const stop = kind => {
      if (done || reason) return;
      reason = kind;
      child.kill('SIGKILL');
      // A pipe held after direct-child exit cannot keep the caller waiting forever.
      hard = setTimeout(() => {
        child.stdin.destroy(); child.stdout.destroy(); child.stderr.destroy(); child.unref();
        finish({ kind: reason, directChildAndPipesSettled: false });
      }, 1000);
    };
    const abort = () => stop('aborted');
    try {
      child = spawnProcess(python, ['-I', '-S', fileURLToPath(new URL('./junit_contract_batch.py', import.meta.url))],
        { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    } catch { finish({ kind: 'start_failed' }); return; }
    child.on('spawn', () => { spawned = true; });
    child.stdin.on('error', () => stop('input_failed'));
    child.stdin.on('finish', () => { inputComplete = true; });
    child.stdout.on('data', chunk => {
      outputBytes += chunk.length;
      if (outputBytes > OUTPUT_LIMIT) stop('output_limit');
      else if (!reason) chunks.push(chunk);
    });
    child.stderr.on('data', chunk => { stderrBytes += chunk.length; stop('unexpected_stderr'); });
    child.on('error', () => {
      if (!spawned) finish({ kind: 'start_failed' });
      else stop('process_error');
    });
    child.on('close', (exitCode, childSignal) => {
      if (reason) finish({ kind: reason, directChildAndPipesSettled: true });
      else if (!inputComplete || childSignal || ![0, 1, 2].includes(exitCode) || stderrBytes) finish({ kind: 'parser_exit' });
      else finish({ kind: 'result', exitCode, stdout: Buffer.concat(chunks), directChildAndPipesSettled: true });
    });
    timer = setTimeout(() => stop('timeout'), timeoutMs);
    signal?.addEventListener('abort', abort, { once: true });
    if (signal?.aborted) abort();
    else child.stdin.end(input);
  });
}

export function validateJunitResponse(output, exitCode, identities) {
  if (!Buffer.isBuffer(output) || output.length > OUTPUT_LIMIT || !isUtf8(output)) throw new Error('invalid_parser_result');
  let result;
  try { result = JSON.parse(output.toString('utf8')); }
  catch { throw new Error('invalid_parser_result'); }
  // The reviewed Python emits canonical fixed ASCII JSON. This rejects duplicate
  // keys, extra whitespace, noncanonical numbers and extra trailing documents.
  if (JSON.stringify(result) + '\n' !== output.toString('utf8') || !fields(result, ['schemaVersion', 'status', 'ok', 'reports'])
    || result.schemaVersion !== 1 || typeof result.ok !== 'boolean' || !Array.isArray(result.reports)) throw new Error('invalid_parser_result');
  if (result.status === 'invalid_input' || result.status === 'limit_exceeded') {
    if (exitCode !== 2 || result.ok || result.reports.length) throw new Error('invalid_parser_result');
    return result;
  }
  if (result.status !== 'compared' || result.reports.length !== identities.length) throw new Error('invalid_parser_result');
  let actualCases = 0;
  result.reports.forEach((row, index) => {
    if (!fields(row, ['index', 'reason', 'artifactSha256', 'expectedContractSha256', 'counts', 'duplicateCaseRows'])
      || row.index !== index || !reasons.includes(row.reason) || row.artifactSha256 !== identities[index].artifactSha256
      || row.expectedContractSha256 !== identities[index].expectedContractSha256 || !Array.isArray(row.counts) || row.counts.length !== 4
      || row.counts.some(count => !Number.isSafeInteger(count) || count < 0 || count > 10_000)
      || row.counts.slice(1).reduce((sum, value) => sum + value, 0) > row.counts[0]
      || !Number.isSafeInteger(row.duplicateCaseRows) || row.duplicateCaseRows < 0 || row.duplicateCaseRows > row.counts[0]) throw new Error('invalid_parser_result');
    actualCases += row.counts[0];
    if (row.reason === 'ok' && (JSON.stringify(row.counts) !== JSON.stringify(identities[index].counts)
      || row.duplicateCaseRows !== identities[index].duplicateCaseRows)) throw new Error('invalid_parser_result');
  });
  if (actualCases > 10_000 || result.ok !== result.reports.every(row => row.reason === 'ok')
    || exitCode !== (result.ok ? 0 : 1)) throw new Error('invalid_parser_result');
  return result;
}

export async function compareJunitEvidence(plans, options) {
  let request;
  try { request = prepareJunitBatch(plans); }
  catch { return { kind: 'input_failed' }; }
  let parser;
  try { parser = await runJunitPython(request.input, options); }
  catch { return { kind: 'invalid_parser_options' }; }
  if (parser.kind !== 'result') return parser;
  try {
    const result = validateJunitResponse(parser.stdout, parser.exitCode, request.identities);
    return { kind: result.status === 'compared' ? 'compared' : 'batch_rejected', result,
      executionAttestation: 'not_provided', processTreeCleanup: 'not_assessed' };
  } catch { return { kind: 'invalid_parser_result' }; }
}

async function main(args) {
  try {
    const options = {};
    for (let index = 0; index < args.length; index += 2) {
      if (!['--python', '--plan'].includes(args[index]) || !args[index + 1] || Object.hasOwn(options, args[index])) throw new Error();
      options[args[index]] = args[index + 1];
    }
    if (Object.keys(options).length !== 2 || !isAbsolute(options['--plan']) || !isAbsolute(options['--python'])) throw new Error();
    // Machine plans are compact JSON plus one newline. Requiring the original
    // bytes to round-trip also rejects duplicate-key plans before file intake.
    const planText = readEvidenceText(options['--plan'], 4 * 1024 * 1024);
    const plans = JSON.parse(planText);
    if (JSON.stringify(plans) + '\n' !== planText) throw new Error();
    const compared = await compareJunitEvidence(plans, { python: options['--python'] });
    process.stdout.write(JSON.stringify(compared) + '\n');
    process.exitCode = compared.kind === 'compared' ? (compared.result.ok ? 0 : 1) : 2;
  } catch {
    process.stdout.write('{"kind":"input_failed"}\n');
    process.exitCode = 2;
  }
}

let isCli = false;
try { isCli = !!process.argv[1] && pathToFileURL(realpathSync(process.argv[1])).href === import.meta.url; }
catch { /* Importing from a REPL need not supply an existing entry point. */ }
if (isCli) await main(process.argv.slice(2));
