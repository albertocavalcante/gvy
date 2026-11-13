// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
const { readEvidenceBytes, readEvidenceText } = await import(pathToFileURL(path.join(process.cwd(), 'tools/ci/read-evidence.mjs')).href);
const [producer, expectedText] = process.argv.slice(2);
const expectedExit = Number(expectedText);
assert.ok(/^[a-z][a-z0-9-]*$/.test(producer) && [0, 1].includes(expectedExit));
const root = path.join(process.env.RUNNER_TEMP, 'receipt-proof', 'gradle');
const paths = [];
const entries = fs.opendirSync(root); let count = 0;
try {
  for (let entry; (entry = entries.readSync()) !== null;) {
    assert.ok(++count <= 4096);
    if (entry.name.startsWith(`${producer}-`)) {
      assert.ok(entry.isDirectory() && !entry.isSymbolicLink());
      paths.push(entry.name);
    }
  }
} finally { entries.closeSync(); }
assert.equal(paths.length, 1);
const directory = path.join(root, paths[0]);
const readJson = name => JSON.parse(readEvidenceText(path.join(directory, name), 2 * 1024 * 1024));
const summary = readJson('summary.json'); const context = readJson('context.json');
assert.equal(summary.producer, producer); assert.deepEqual(summary.context, context);
assert.equal(context.headSha, process.env.TESTED_SHA); assert.equal(context.testedSha, process.env.TESTED_SHA);
assert.equal(context.command, 'gradle'); assert.equal(context.runId, process.env.GITHUB_RUN_ID);
assert.equal(context.runAttempt, Number(process.env.GITHUB_RUN_ATTEMPT));
assert.match(context.invocationId, /^[0-9a-f-]{36}$/); assert.equal(paths[0], `${producer}-${context.invocationId}`);
assert.equal(summary.worktreeDirty, ['receipt-interrupt-red', 'receipt-format'].includes(producer)); assert.equal(summary.complete, true);
assert.equal(summary.commandExitCode, expectedExit); assert.equal(summary.captureExitCode, 0);
assert.equal(summary.ok, expectedExit === 0); assert.deepEqual(summary.unexpected, []);
assert.deepEqual(summary.errors, expectedExit === 0 ? [] : ['Gradle command failed with exit 1']);
const raw = readEvidenceBytes(path.join(directory, 'raw.log'), 128 * 1024 * 1024);
assert.equal(raw.length, summary.log.bytes); assert.equal(createHash('sha256').update(raw).digest('hex'), summary.log.sha256);
assert.deepEqual(readEvidenceBytes(path.join(directory, 'console.log'), 128 * 1024 * 1024), raw);
console.log(directory);
