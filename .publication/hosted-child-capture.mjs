// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
// Review draft: invoke only in a private, owned runner workspace.
import assert from 'node:assert/strict';
import { assessObservations, assessSeal } from './hosted-child-assess.mjs';
import fs from 'node:fs/promises';
import { constants } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const [sourceArg, mode, parentArg, ...argv] = process.argv.slice(2);
assert.ok(['green', 'wrong-location'].includes(mode));
const source = resolve(sourceArg);
const parent = resolve(parentArg);
const load = name => import(pathToFileURL(join(source, 'tools/perf', `${name}.mjs`)).href);
const [{ parseVerificationOptions }, { preflightVerification }, { inspectPreparedClasspath }, { executeVerification }, { unpackAgentOutcome }, { readSealedOutcomeSnapshot, closeSealedOutcome }, { runVerificationProcess }] = await Promise.all([
  load('verification-options'), load('verification-preflight'), load('prepared-classpath'), load('verification-execution'), load('agent-outcome-envelope'), load('verification-outcome-file'), load('verification-process'),
]);
const options = parseVerificationOptions(argv);
assert.equal(await fs.realpath(options.source), await fs.realpath(source));
const parentInfo = await fs.lstat(parent);
assert.ok(parentInfo.isDirectory() && !parentInfo.isSymbolicLink());
assert.equal(parentInfo.mode & 0o077, 0);
if (process.getuid) assert.equal(parentInfo.uid, process.getuid());
assert.equal(await fs.realpath(parent), parent); // Runner owns this synthetic directory and its ancestors.
let result;
let originalSeal;
let capturedNonce;
let originalRetained = false;
let processRetained = false;
try {
const check = await preflightVerification(options);
assert.equal(check.ready, true);
const before = await inspectPreparedClasspath(options);
assert.equal(before.status, 'verified');
const readSnapshot = async (owned, nonce) => {
  const snapshot = await readSealedOutcomeSnapshot(owned, nonce);
  try {
    assert.equal(snapshot.path, join(dirname(owned.path), 'outcome.seal'));
    assert.equal(dirname(dirname(owned.path)), parent);
    const handle = await fs.open(snapshot.path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    try {
    const stat = await handle.stat({ bigint: true });
    assert.ok(stat.isFile() && stat.dev === snapshot.dev && stat.ino === snapshot.ino && stat.size > 0n && stat.size <= 512n);
    assert.equal(stat.mode & 0o077n, 0n);
    if (process.getuid) assert.equal(stat.uid, BigInt(process.getuid()));
    const bytes = Buffer.alloc(Number(stat.size));
    let offset = 0;
    while (offset < bytes.length) {
      const { bytesRead } = await handle.read(bytes, offset, bytes.length - offset, null);
      assert.ok(bytesRead > 0); offset += bytesRead;
    }
    const after = await handle.stat({ bigint: true });
    assert.equal(after.size, stat.size); assert.equal(after.mtimeNs, stat.mtimeNs); assert.equal(after.ctimeNs, stat.ctimeNs);
    originalSeal = bytes.toString('utf8');
    assert.equal(JSON.parse(originalSeal).nonce, nonce);
    capturedNonce = nonce;
    } finally { await handle.close(); }
    const payload = Buffer.from(snapshot.text, 'utf8');
    assessSeal(originalSeal, capturedNonce, payload);
    await fs.writeFile(join(parent, `${mode}-sealed-original.json`), JSON.stringify({ outcome: snapshot.text, originalSeal }), { mode: 0o600, flag: 'wx' });
    originalRetained = true;
    return snapshot;
  } catch (failure) {
    try { await closeSealedOutcome(snapshot); } catch (close) { throw new AggregateError([failure, close], 'CAPTURE_CLEANUP_FAILED'); }
    throw failure;
  }
};
const runProcess = async (...args) => {
  const child = await runVerificationProcess(...args);
  await fs.writeFile(join(parent, `${mode}-process.json`), JSON.stringify({ status: child.status, exitCode: child.exitCode, directTerminated: child.directTerminated, cleanup: child.cleanup, elapsedMs: child.elapsedMs }), { mode: 0o600, flag: 'wx' });
  processRetained = true;
  return child;
};
result = await executeVerification({ ...options, preparedClasspath: before.classpath }, parent, { readSnapshot, runProcess });
await fs.writeFile(join(parent, `${mode}-original.json`), JSON.stringify({
  process: { status: result.process.status, exitCode: result.process.exitCode, directTerminated: result.process.directTerminated, cleanup: result.process.cleanup, elapsedMs: result.process.elapsedMs },
  outcome: result.outcome, originalSeal: originalSeal ?? null,
}, null, 2), { mode: 0o600, flag: 'wx' });
assert.equal(result.process.status, 'completed');
assert.equal(result.process.directTerminated, true);
assert.equal(result.process.cleanup, 'unconfirmed');
assert.equal(result.temporaryState, 'retained_unconfirmed');
assert.equal(typeof result.outcome, 'string');
assert.equal((await inspectPreparedClasspath(options)).status, 'verified');
const payload = Buffer.from(result.outcome, 'utf8');
assert.equal(new TextDecoder('utf-8', { fatal: true }).decode(payload), result.outcome);
assert.ok(payload.length <= 4 * 1024 * 1024);
assert.equal(typeof originalSeal, 'string');
assessSeal(originalSeal, capturedNonce, payload);
const unpacked = unpackAgentOutcome(result.outcome);
assert.equal(unpacked.launch.status, 'observed');
for (const phase of [unpacked.launch.before, unpacked.launch.afterStart]) {
  assert.equal(phase.artifact, 'verified');
  assert.equal(phase.source, 'verified');
  assert.equal(phase.observedDigest, options.sha256);
  assert.equal(phase.sourceCommit, options.sourceCommit);
  assert.equal(phase.sourceDirty, false);
}
const outcome = JSON.parse(unpacked.outcome);
assessObservations(outcome, mode);
await fs.writeFile(join(parent, `${mode}-capture.json`), JSON.stringify({ process: { status: result.process.status, exitCode: result.process.exitCode, directTerminated: result.process.directTerminated, cleanup: result.process.cleanup, elapsedMs: result.process.elapsedMs }, outcome: result.outcome, originalSeal }, null, 2), { mode: 0o600, flag: 'wx' });

} catch (failure) {
  await fs.writeFile(join(parent, `${mode}-failure.json`), JSON.stringify({ status: 'capture_failed', originalRetained, processRetained }), { mode: 0o600, flag: 'wx' });
  throw failure;
}
