import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import test from 'node:test';
import {
  applyByteEdit, byteOffsetToTreeSitterPoint, byteOffsetToUtf16Position, normalizeSyntaxSpans,
  sourceSha256, utf16PositionToByteOffset, validateQualitySuite, verifySyntaxObservation,
} from './quality.mjs';

const suite = JSON.parse(await fs.readFile(new URL('authored.json', import.meta.url), 'utf8'));
const fixture = id => suite.fixtures.find(value => value.id === id);
const spans = value => value.expectation.spans.map(({ startByte, endByte, category }) => ({ startByte, endByte, category }));
const observation = value => ({ fixtureId: value.id, sourceSha256: value.sourceSha256,
  documentVersion: value.expectation.documentVersion, completed: true, outcome: value.expectation.outcome,
  spans: spans(value) });
const state = value => ({ source: value.source, sourceSha256: value.sourceSha256,
  documentVersion: value.expectation.documentVersion });

test('all hand-authored expectations and deterministic edit targets are internally valid', () => {
  assert.deepEqual(validateQualitySuite(suite), {
    fixtures: 9, traces: 4, exactFixtures: 7, requiredOnlyFixtures: 2,
    compilerCompatibility: 'not-executed', semanticAnchors: 'metadata-only',
  });
});

test('independently authored lexical observations pass only their declared assertion scope', () => {
  for (const value of suite.fixtures) {
    const result = verifySyntaxObservation(value, observation(value));
    assert.equal(result.exactLexicalSpansChecked, value.expectation.assertionMode === 'exact');
    assert.equal(result.semanticChecked, false);
  }
});

test('empty and nonempty partial output cannot satisfy a nonempty exact oracle', () => {
  const value = fixture('basic');
  assert.throws(() => verifySyntaxObservation(value, { ...observation(value), spans: [] }),
    { message: 'QUALITY_SPAN_MISMATCH' });
  assert.throws(() => verifySyntaxObservation(value, { ...observation(value), spans: spans(value).slice(0, -1) }),
    { message: 'QUALITY_SPAN_MISMATCH' });
});

test('same-length keyword and string ranges cannot be swapped', () => {
  const value = fixture('corrected');
  const wrong = observation(value);
  wrong.spans[0].category = 'string';
  wrong.spans[2].category = 'keyword';
  assert.throws(() => verifySyntaxObservation(value, wrong), { message: 'QUALITY_SPAN_MISMATCH' });
});

test('source identity, document version and completion must match', () => {
  const value = fixture('basic');
  assert.throws(() => verifySyntaxObservation(value, { ...observation(value), sourceSha256: '0'.repeat(64) }),
    { message: 'QUALITY_STALE_SOURCE' });
  assert.throws(() => verifySyntaxObservation(value, { ...observation(value), documentVersion: 0 }),
    { message: 'QUALITY_STALE_VERSION' });
  assert.throws(() => verifySyntaxObservation(value, { ...observation(value), completed: false }),
    { message: 'QUALITY_INCOMPLETE' });
});

test('undeclared recovery cannot replace a valid result', () => {
  const value = fixture('basic');
  assert.throws(() => verifySyntaxObservation(value, { ...observation(value), outcome: 'recovered' }),
    { message: 'QUALITY_OUTCOME_MISMATCH' });
});

test('unknown categories and UTF-8 interior positions fail before comparing spans', () => {
  const value = fixture('unicode-crlf');
  const unknown = observation(value);
  unknown.spans[0].category = 'variable';
  assert.throws(() => verifySyntaxObservation(value, unknown), { message: 'QUALITY_INVALID_SPAN' });
  const interior = observation(value);
  interior.spans[0].startByte = 4;
  assert.throws(() => verifySyntaxObservation(value, interior), { message: 'QUALITY_INVALID_SPAN' });
});

test('raw overlapping captures are not accepted as resolved highlights', () => {
  assert.throws(() => normalizeSyntaxSpans('def', [
    { startByte: 0, endByte: 3, category: 'keyword' },
    { startByte: 1, endByte: 2, category: 'string' },
  ]), { message: 'QUALITY_OVERLAPPING_SPANS' });
});

test('adjacent equal categories normalize without hiding differently colored ranges', () => {
  assert.deepEqual(normalizeSyntaxSpans('def', [
    { startByte: 2, endByte: 3, category: 'keyword' },
    { startByte: 0, endByte: 2, category: 'keyword' },
  ]), [{ startByte: 0, endByte: 3, category: 'keyword' }]);
  assert.deepEqual(normalizeSyntaxSpans('def', [
    { startByte: 0, endByte: 2, category: 'keyword' },
    { startByte: 2, endByte: 3, category: 'string' },
  ]), [
    { startByte: 0, endByte: 2, category: 'keyword' },
    { startByte: 2, endByte: 3, category: 'string' },
  ]);
});

test('required-only recovery anchors allow extra output without certifying completeness', () => {
  const value = fixture('unterminated');
  const extra = observation(value);
  extra.spans.push({ startByte: 12, endByte: 14, category: 'string' });
  const result = verifySyntaxObservation(value, extra);
  assert.equal(result.exactLexicalSpansChecked, false);
  assert.throws(() => verifySyntaxObservation(value, { ...extra, spans: extra.spans.slice(1) }),
    { message: 'QUALITY_SPAN_MISMATCH' });
});

test('empty and whitespace-only input have explicit empty exact expectations', () => {
  for (const id of ['empty', 'whitespace']) {
    assert.equal(verifySyntaxObservation(fixture(id), observation(fixture(id))).exactLexicalSpansChecked, true);
  }
});

test('unknown schema fields, missing fixtures and changed oracle text fail closed', () => {
  assert.throws(() => validateQualitySuite({ ...suite, extra: true }), { message: 'QUALITY_INVALID_SUITE' });
  const missing = structuredClone(suite);
  missing.fixtures.pop();
  assert.throws(() => validateQualitySuite(missing), { message: 'QUALITY_INVALID_SUITE' });
  const wrong = structuredClone(suite);
  wrong.fixtures[0].expectation.spans[0].text = 'xxx';
  assert.throws(() => validateQualitySuite(wrong), { message: 'QUALITY_INVALID_EXPECTATION' });
});

test('authored source and edit-target digests are checked rather than trusted', () => {
  const wrongSource = structuredClone(suite);
  wrongSource.fixtures[0].source = ` ${wrongSource.fixtures[0].source}`;
  assert.throws(() => validateQualitySuite(wrongSource), { message: 'QUALITY_SOURCE_IDENTITY' });
  const wrongTarget = structuredClone(suite);
  wrongTarget.traces[0].expectedSource = 'different';
  assert.throws(() => validateQualitySuite(wrongTarget), { message: 'QUALITY_SOURCE_IDENTITY' });
});

test('UTF-8 bytes and UTF-16 positions count astral and combining characters explicitly', () => {
  const source = fixture('unicode-crlf').source;
  assert.deepEqual(byteOffsetToUtf16Position(source, 3), { line: 0, character: 3 });
  assert.deepEqual(byteOffsetToUtf16Position(source, 7), { line: 0, character: 5 });
  assert.deepEqual(byteOffsetToUtf16Position(source, 8), { line: 0, character: 6 });
  assert.deepEqual(byteOffsetToUtf16Position(source, 9), { line: 0, character: 7 });
  assert.deepEqual(byteOffsetToUtf16Position(source, 11), { line: 0, character: 8 });
  assert.equal(utf16PositionToByteOffset(source, { line: 1, character: 6 }), 22);
  assert.equal(utf16PositionToByteOffset(source, { line: 1, character: 10 }), 27);
  assert.deepEqual(byteOffsetToUtf16Position(source, 64), { line: 2, character: 31 });
  assert.throws(() => utf16PositionToByteOffset(source, { line: 0, character: 4 }),
    { message: 'QUALITY_INVALID_UTF16_POSITION' });
  assert.throws(() => byteOffsetToUtf16Position(source, 4), { message: 'QUALITY_INVALID_BYTE_OFFSET' });
});

test('CRLF interior has a native byte point but no LSP position', () => {
  const source = fixture('unicode-crlf').source;
  assert.deepEqual(byteOffsetToUtf16Position(source, 14), { line: 0, character: 11 });
  assert.throws(() => byteOffsetToUtf16Position(source, 15), { message: 'QUALITY_UNREPRESENTABLE_LINE_ENDING' });
  assert.deepEqual(byteOffsetToTreeSitterPoint(source, 15), { row: 0, column: 15 });
  assert.deepEqual(byteOffsetToTreeSitterPoint(source, 16), { row: 1, column: 0 });
  assert.deepEqual(byteOffsetToUtf16Position(source, 16), { line: 1, character: 0 });
});

test('native rows count LF while LSP lines also recognize a lone CR', () => {
  assert.deepEqual(byteOffsetToUtf16Position('a\rb', 2), { line: 1, character: 0 });
  assert.deepEqual(byteOffsetToTreeSitterPoint('a\rb', 2), { row: 0, column: 2 });
  assert.deepEqual(byteOffsetToTreeSitterPoint('a\nb', 2), { row: 1, column: 0 });
});

test('every representable authored byte boundary round-trips without normalizing source', () => {
  for (const value of suite.fixtures) {
    for (let byte = 0; byte <= value.byteLength; byte += 1) {
      try {
        const position = byteOffsetToUtf16Position(value.source, byte);
        assert.equal(utf16PositionToByteOffset(value.source, position), byte);
      } catch (error) {
        assert.ok(['QUALITY_INVALID_BYTE_OFFSET', 'QUALITY_UNREPRESENTABLE_LINE_ENDING'].includes(error.message));
      }
    }
  }
  assert.notEqual(sourceSha256('é'), sourceSha256('é'));
});

test('lone surrogates, invalid positions and oversized sources are rejected', () => {
  assert.throws(() => sourceSha256('\ud800'), { message: 'QUALITY_INVALID_SOURCE' });
  assert.throws(() => utf16PositionToByteOffset('x', { line: 0, character: 2 }),
    { message: 'QUALITY_INVALID_UTF16_POSITION' });
  assert.throws(() => byteOffsetToTreeSitterPoint('x', -1), { message: 'QUALITY_INVALID_BYTE_OFFSET' });
  assert.throws(() => sourceSha256('x'.repeat(1024 * 1024 + 1)), { message: 'QUALITY_SIZE_LIMIT' });
});

test('all edit traces reproduce independently authored target text and digests', () => {
  for (const trace of suite.traces) {
    const result = applyByteEdit(state(fixture(trace.initialFixtureId)), trace.edit);
    assert.equal(result.source, trace.expectedSource);
    assert.equal(result.sourceSha256, trace.expectedSourceSha256);
    assert.equal(result.documentVersion, trace.edit.toVersion);
  }
});

test('replaying an edit against the advanced version is stale rather than a no-op sample', () => {
  const trace = suite.traces[0];
  const next = applyByteEdit(state(fixture(trace.initialFixtureId)), trace.edit);
  assert.throws(() => applyByteEdit(next, trace.edit), { message: 'QUALITY_STALE_VERSION' });
});

test('edits require the original source digest, scalar boundaries and useful change', () => {
  const value = fixture('unicode-crlf');
  const trace = suite.traces.find(item => item.id === 'unicode-rename');
  assert.throws(() => applyByteEdit(state(value), { ...trace.edit, sourceSha256: '0'.repeat(64) }),
    { message: 'QUALITY_STALE_SOURCE' });
  assert.throws(() => applyByteEdit(state(value), { ...trace.edit, startByte: 4 }),
    { message: 'QUALITY_INVALID_EDIT_RANGE' });
  assert.throws(() => applyByteEdit(state(value), { ...trace.edit, replacement: 'Café' }),
    { message: 'QUALITY_NO_OP_EDIT' });
});

test('excessive observations are bounded before span normalization', () => {
  assert.throws(() => normalizeSyntaxSpans('x', Array(65537).fill({ startByte: 0, endByte: 1, category: 'keyword' })),
    { message: 'QUALITY_SIZE_LIMIT' });
});
