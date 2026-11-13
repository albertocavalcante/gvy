// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { lstatSync, mkdirSync, readFileSync, readdirSync, realpathSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { gunzipSync } from 'node:zlib';

const base = '2a762e982c227ee806f3a96cf3d8216bfdee0b4b';
const baselineTree = '74a92727885379d4e0682dbd161bea4b600b2261';
const testsTree = '238f1304c542168ab76239fdeeb9d2facc4444c0';
const sha = bytes => createHash('sha256').update(bytes).digest('hex');

function boundedFile(path, limit) {
  const stat = lstatSync(path);
  assert(stat.isFile() && !stat.isSymbolicLink() && stat.size <= limit, `Invalid evidence file: ${path}`);
  return readFileSync(path);
}

export function verifyXmlDocuments(documents, expected, mode) {
  assert(['green', 'red'].includes(mode));
  // Use the platform XML parser; no regex parser or dependency download is needed.
  const script = String.raw`
import json, sys, xml.etree.ElementTree as ET
spec = json.load(sys.stdin)
wanted = {(case['classname'], case['name']): case for case in spec['expected']}
assert len(wanted) == len(spec['expected']) and wanted, 'duplicate/empty expected cases'
seen = set()
for text in spec['documents']:
    assert '<!DOCTYPE' not in text and '<!ENTITY' not in text, 'XML declarations are forbidden'
    root = ET.fromstring(text)
    assert root.tag == 'testsuite', 'expected a JUnit testsuite'
    cases = root.findall('testcase')
    assert sum(1 for _ in root.iter('testsuite')) == 1, 'nested testsuites are forbidden'
    assert sum(1 for _ in root.iter('testcase')) == len(cases), 'hidden testcases are forbidden'
    for marker in ('failure', 'error', 'skipped'):
        all_markers = sum(1 for _ in root.iter(marker))
        direct_markers = sum(len(case.findall(marker)) for case in cases)
        assert all_markers == direct_markers, 'stray/nested result markers are forbidden'
    assert not root.findall('.//error') and not root.findall('.//skipped'), 'errors/skips cannot prove a result'
    assert int(root.attrib['tests']) == len(cases), 'test count mismatch'
    assert int(root.attrib['errors']) == 0 and int(root.attrib.get('skipped', '0')) == 0
    assert int(root.attrib['failures']) == sum(len(case.findall('failure')) for case in cases)
    for case in cases:
        key = (case.attrib['classname'], case.attrib['name'])
        assert key in wanted and key not in seen, 'foreign/duplicate testcase'
        seen.add(key)
        failures = case.findall('failure')
        if spec['mode'] == 'green':
            assert not failures, 'baseline must pass'
        else:
            assert len(failures) == 1, 'each selected RED must fail exactly once'
            failure = failures[0]
            assert failure.attrib.get('type') == 'org.opentest4j.AssertionFailedError', 'not an assertion RED'
            message = failure.attrib.get('message', '')
            assert wanted[key]['message'] in message, 'wrong assertion failure'
assert seen == set(wanted), 'missing selected testcases'
print(json.dumps({'mode': spec['mode'], 'tests': len(seen), 'failures': len(seen) if spec['mode'] == 'red' else 0}))
`;
  const parsed = spawnSync('python3', ['-c', script], {
    input: JSON.stringify({ documents, expected, mode }), encoding: 'utf8',
    maxBuffer: 1024 * 1024, timeout: 10_000,
  });
  assert.equal(parsed.status, 0, `JUnit proof failed: ${parsed.stderr}`);
  return JSON.parse(parsed.stdout);
}

function prepare(support, output) {
  const bytes = boundedFile(join(support, 'receipt.json'), 64 * 1024);
  const receipt = JSON.parse(bytes);
  assert.equal(receipt.schemaVersion, 1);
  assert.equal(receipt.base, base);
  assert.equal(receipt.baselineTree, baselineTree);
  assert.equal(receipt.testsTree, testsTree);
  assert.equal(receipt.baselineCases.length, 11);
  assert.equal(receipt.redCases.length, 2);
  assert.deepEqual(Object.keys(receipt.patches).sort(), ['baseline', 'testsFirst']);
  for (const [label, patch] of Object.entries(receipt.patches)) {
    assert.equal(patch.file, `${label}.patch.gz`);
    const zipped = boundedFile(join(support, patch.file), 64 * 1024);
    assert.equal(zipped.length, patch.payloadBytes);
    assert.equal(sha(zipped), patch.payloadSha256);
    const raw = gunzipSync(zipped, { maxOutputLength: 128 * 1024 });
    assert.equal(raw.length, patch.patchBytes);
    assert.equal(sha(raw), patch.patchSha256);
    writeFileSync(join(output, `${label}.patch`), raw, { flag: 'wx' });
  }
  writeFileSync(join(output, 'receipt.json'), bytes, { flag: 'wx' });
}

function snapshot(directory, wrapperExit) {
  mkdirSync(directory, { recursive: true });
  writeFileSync(join(directory, 'wrapper-exit.json'), JSON.stringify({ wrapperExit }) + '\n', { flag: 'wx' });
  const source = 'gls/build/test-results/test';
  const output = join(directory, 'original-results');
  mkdirSync(output);
  let names;
  try { names = readdirSync(source).filter(name => name.endsWith('.xml')).sort(); }
  catch (error) { if (error.code !== 'ENOENT') throw error; names = []; }
  assert(names.length <= 32, 'Too many JUnit XML files');
  const files = names.map(name => {
    const bytes = boundedFile(join(source, name), 2 * 1024 * 1024);
    writeFileSync(join(output, name), bytes, { flag: 'wx' });
    return { name, bytes: bytes.length, sha256: sha(bytes) };
  });
  writeFileSync(join(directory, 'xml-snapshot.json'), JSON.stringify(files, null, 2) + '\n', { flag: 'wx' });
}

function verify(directory, kind) {
  assert(['baseline', 'compile', 'red', 'lint-fix', 'lint'].includes(kind));
  const receipt = JSON.parse(boundedFile(join(dirname(directory), 'receipt.json'), 64 * 1024));
  const producer = `q04-authority-${kind}`;
  const children = readdirSync(directory).filter(name => name.startsWith(producer + '-'));
  assert.equal(children.length, 1, 'Exactly one canonical invocation is required');
  assert.match(children[0], new RegExp(`^${producer}-[0-9a-f-]{36}$`));
  const invocation = join(directory, children[0]);
  const report = JSON.parse(boundedFile(join(invocation, 'diagnostics.json'), 2 * 1024 * 1024));
  const context = JSON.parse(boundedFile(join(invocation, 'context.json'), 64 * 1024));
  const raw = boundedFile(join(invocation, 'raw.log'), 128 * 1024 * 1024);
  assert(raw.equals(boundedFile(join(invocation, 'console.log'), 128 * 1024 * 1024)));
  assert.deepEqual(report.log, { bytes: raw.length, sha256: sha(raw) });
  assert.deepEqual(report.context, context);
  assert.equal(context.headSha, base);
  assert.equal(context.testedSha, base);
  assert.equal(context.runId, process.env.GITHUB_RUN_ID);
  assert.equal(context.runAttempt, Number(process.env.GITHUB_RUN_ATTEMPT));
  assert.equal(context.command, 'gradle');
  assert.equal(children[0], producer + '-' + context.invocationId);
  assert.equal(report.producer, producer);
  assert.equal(report.worktreeDirty, true);
  assert.equal(report.complete, true);
  assert.equal(report.captureExitCode, 0);
  assert.deepEqual(report.unexpected, []);
  const expectedExit = kind === 'red' ? 1 : 0;
  assert.equal(report.commandExitCode, expectedExit);
  assert.equal(JSON.parse(boundedFile(join(directory, 'wrapper-exit.json'), 1024)).wrapperExit, expectedExit);
  assert.equal(report.ok, kind !== 'red');
  assert.deepEqual(report.errors, kind === 'red' ? ['Gradle command failed with exit 1'] : []);
  if (kind === 'baseline' || kind === 'red') {
    const files = JSON.parse(boundedFile(join(directory, 'xml-snapshot.json'), 64 * 1024));
    const documents = files.map(file => {
      const bytes = boundedFile(join(directory, 'original-results', file.name), 2 * 1024 * 1024);
      assert.equal(bytes.length, file.bytes);
      assert.equal(sha(bytes), file.sha256);
      return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    });
    const xml = verifyXmlDocuments(documents, kind === 'baseline' ? receipt.baselineCases : receipt.redCases,
      kind === 'baseline' ? 'green' : 'red');
    writeFileSync(join(directory, 'verified-junit.json'), JSON.stringify(xml, null, 2) + '\n', { flag: 'wx' });
  }
  writeFileSync(join(directory, 'verified-producer.json'), JSON.stringify({ producer, context, commandExitCode: expectedExit }) + '\n', { flag: 'wx' });
}

function main(args) {
  if (args[0] === 'prepare') return prepare(args[1], args[2]);
  if (args[0] === 'snapshot') return snapshot(args[1], Number(args[2]));
  if (args[0] === 'verify') return verify(args[1], args[2]);
  throw new Error('Expected prepare, snapshot or verify');
}

if (process.argv[1] && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href) main(process.argv.slice(2));
