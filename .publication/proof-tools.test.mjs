// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { verifyXmlDocuments } from './proof-tools.mjs';

const expected = [{ classname: 'example.Control', name: 'rejects stale source()', message: 'stale source rejected' }];
const suite = (body, failures = 0, skipped = 0) =>
  `<testsuite tests="1" failures="${failures}" errors="0" skipped="${skipped}"><testcase classname="example.Control" name="rejects stale source()">${body}</testcase></testsuite>`;
const assertion = '<failure type="org.opentest4j.AssertionFailedError" message="stale source rejected ==&gt; expected: &lt;null&gt;"/>';

test('accepts exactly the expected passing baseline case', () => {
  assert.deepEqual(verifyXmlDocuments([suite('')], expected, 'green'), { mode: 'green', tests: 1, failures: 0 });
});

test('accepts an escaped intended assertion RED', () => {
  assert.deepEqual(verifyXmlDocuments([suite(assertion, 1)], expected, 'red'), { mode: 'red', tests: 1, failures: 1 });
});

test('rejects passing RED controls and failing baseline controls', () => {
  assert.throws(() => verifyXmlDocuments([suite('')], expected, 'red'));
  assert.throws(() => verifyXmlDocuments([suite(assertion, 1)], expected, 'green'));
});

test('rejects operational failures and wrong assertion messages as RED', () => {
  for (const body of [
    '<failure type="java.lang.IllegalStateException" message="stale source rejected"/>',
    '<failure type="org.opentest4j.AssertionFailedError" message="wrong assertion"/>',
  ]) assert.throws(() => verifyXmlDocuments([suite(body, 1)], expected, 'red'));
});

test('rejects a wrong top-level assertion whose cause contains the expected message', () => {
  const unrelated = '<failure type="org.opentest4j.AssertionFailedError" message="cleanup failed">' +
    'Caused by: org.opentest4j.AssertionFailedError: stale source rejected</failure>';
  assert.throws(() => verifyXmlDocuments([suite(unrelated, 1)], expected, 'red'));
});

test('rejects skips, missing, duplicate and foreign cases', () => {
  for (const documents of [
    [suite('<skipped/>', 0, 1)], [], [suite(''), suite('')],
    [suite('').replace('example.Control', 'example.Foreign')],
  ]) assert.throws(() => verifyXmlDocuments(documents, expected, 'green'));
});

test('rejects malformed XML, inconsistent counts and declarations', () => {
  for (const xml of [suite('').slice(0, -2), suite('').replace('tests="1"', 'tests="2"'),
    '<!DOCTYPE testsuite>' + suite('')]) assert.throws(() => verifyXmlDocuments([xml], expected, 'green'));
});

const appendToSuite = (xml, extra) => xml.replace('</testsuite>', extra + '</testsuite>');
const hiddenCase = '<testcase classname="example.Foreign" name="hidden()"/>';
const hiddenFailure = '<failure type="org.opentest4j.AssertionFailedError" message="hidden failure"/>';
const hiddenFailingCase = hiddenCase.replace('/>', `>${hiddenFailure}</testcase>`);
const nestedRedFailure = assertion.replace('/>', `>${hiddenFailure}</failure>`);
for (const [name, xml, mode] of [
  ['nested suites', appendToSuite(suite(''), '<testsuite tests="0" failures="0" errors="0"/>'), 'green'],
  ['hidden passing cases', appendToSuite(suite(''), `<extension>${hiddenCase}</extension>`), 'green'],
  ['hidden failing cases', appendToSuite(suite(''), `<extension>${hiddenFailingCase}</extension>`), 'green'],
  ['stray suite failures', appendToSuite(suite(''), hiddenFailure), 'green'],
  ['failures nested below a testcase extension', suite(`<extension>${hiddenFailure}</extension>`), 'green'],
  ['stray failures alongside the intended RED', appendToSuite(suite(assertion, 1), hiddenFailure), 'red'],
  ['failures nested inside the intended failure', suite(nestedRedFailure, 1), 'red'],
  ['stray errors', appendToSuite(suite(assertion, 1), '<extension><error/></extension>'), 'red'],
  ['stray skipped markers', appendToSuite(suite(''), '<extension><skipped/></extension>'), 'green'],
]) {
  test(`rejects ${name} despite matching direct inventory`, () => {
    assert.throws(() => verifyXmlDocuments([xml], expected, mode));
  });
}

test('CLI prepares actual payloads when invoked through an alias', () => {
  const fixture = mkdtempSync(join(tmpdir(), 'gvy-q04-proof-cli-'));
  try {
    const helper = fileURLToPath(new URL('./proof-tools.mjs', import.meta.url));
    const support = fileURLToPath(new URL('.', import.meta.url));
    const alias = join(fixture, 'proof-alias.mjs');
    const output = join(fixture, 'output');
    mkdirSync(output);
    symlinkSync(helper, alias);
    const result = spawnSync(process.execPath, [alias, 'prepare', support, output], {
      encoding: 'utf8', timeout: 10_000, maxBuffer: 1024 * 1024,
    });
    assert.equal(result.status, 0, result.stderr);
    assert(existsSync(join(output, 'baseline.patch')), 'CLI must materialize the baseline patch');
    assert(readFileSync(join(output, 'baseline.patch')).length > 0, 'CLI must materialize the baseline patch');
    assert(readFileSync(join(output, 'testsFirst.patch')).length > 0, 'CLI must materialize the tests-first patch');
  } finally {
    rmSync(fixture, { recursive: true });
  }
});
