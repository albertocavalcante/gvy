// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { createHash } from 'node:crypto';

const MAX_BYTES = 1024 * 1024;
const MAX_SPANS = 65536;
const CATEGORIES = ['keyword', 'comment', 'string', 'number', 'operator', 'punctuation'];
const FIXTURES = ['basic', 'unicode-crlf', 'semantic-core', 'empty', 'whitespace', 'unterminated',
  'corrected', 'gstrings-dollar', 'slashy'].sort();
const TRACES = ['repair-string', 'unicode-rename', 'crlf-to-lf', 'grow-prefix'].sort();
const equal = (left, right) => JSON.stringify(left) === JSON.stringify(right);
const fail = code => { throw new Error(code); };
const integer = value => Number.isSafeInteger(value) && value >= 0;
const digest = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);

function object(value, keys, code) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
    || Object.getPrototypeOf(value) !== Object.prototype
    || !equal(Object.keys(value).sort(), [...keys].sort())) fail(code);
}

function validateSource(source) {
  if (typeof source !== 'string') fail('QUALITY_INVALID_SOURCE');
  if (Buffer.byteLength(source, 'utf8') > MAX_BYTES) fail('QUALITY_SIZE_LIMIT');
  for (let index = 0; index < source.length; index += 1) {
    const unit = source.charCodeAt(index);
    if (unit >= 0xd800 && unit <= 0xdbff) {
      const next = source.charCodeAt(index + 1);
      if (!(next >= 0xdc00 && next <= 0xdfff)) fail('QUALITY_INVALID_SOURCE');
      index += 1;
    } else if (unit >= 0xdc00 && unit <= 0xdfff) fail('QUALITY_INVALID_SOURCE');
  }
}

function sourceBytes(source) {
  validateSource(source);
  return Buffer.from(source, 'utf8');
}

export function sourceSha256(source) {
  return createHash('sha256').update(sourceBytes(source)).digest('hex');
}

function byteBoundaries(bytes) {
  const flags = new Uint8Array(bytes.length + 1);
  for (let byte = 0; byte < bytes.length; byte += 1) {
    if ((bytes[byte] & 0xc0) !== 0x80) flags[byte] = 1;
  }
  flags[bytes.length] = 1;
  return flags;
}

/** Resolves representation only; captures must already have their precedence resolved. */
export function normalizeSyntaxSpans(source, spans) {
  const bytes = sourceBytes(source);
  const boundaries = byteBoundaries(bytes);
  if (!Array.isArray(spans)) fail('QUALITY_INVALID_SPAN');
  if (spans.length > MAX_SPANS) fail('QUALITY_SIZE_LIMIT');
  const sorted = spans.map(span => {
    object(span, ['startByte', 'endByte', 'category'], 'QUALITY_INVALID_SPAN');
    if (!integer(span.startByte) || !integer(span.endByte) || span.endByte <= span.startByte
      || span.endByte > bytes.length || !boundaries[span.startByte] || !boundaries[span.endByte]
      || !CATEGORIES.includes(span.category)) fail('QUALITY_INVALID_SPAN');
    return { startByte: span.startByte, endByte: span.endByte, category: span.category };
  }).sort((left, right) => left.startByte - right.startByte || left.endByte - right.endByte);
  const result = [];
  for (const span of sorted) {
    const previous = result.at(-1);
    if (previous && span.startByte < previous.endByte) fail('QUALITY_OVERLAPPING_SPANS');
    if (previous && span.startByte === previous.endByte && span.category === previous.category) {
      previous.endByte = span.endByte;
    } else result.push(span);
  }
  return result;
}

function expectedRange(bytes, boundaries, value, keys) {
  object(value, keys, 'QUALITY_INVALID_EXPECTATION');
  if (!integer(value.startByte) || !integer(value.endByte) || value.endByte <= value.startByte
    || value.endByte > bytes.length || !boundaries[value.startByte] || !boundaries[value.endByte]
    || typeof value.text !== 'string'
    || bytes.subarray(value.startByte, value.endByte).toString('utf8') !== value.text) {
    fail('QUALITY_INVALID_EXPECTATION');
  }
}

function validateFixture(fixture) {
  object(fixture, ['id', 'source', 'sourceSha256', 'byteLength', 'compilerProfile', 'expectation'],
    'QUALITY_INVALID_EXPECTATION');
  const bytes = sourceBytes(fixture.source);
  if (!digest(fixture.sourceSha256) || sourceSha256(fixture.source) !== fixture.sourceSha256
    || fixture.byteLength !== bytes.length) fail('QUALITY_SOURCE_IDENTITY');
  if (!FIXTURES.includes(fixture.id)) fail('QUALITY_INVALID_EXPECTATION');
  const expected = fixture.expectation;
  object(expected, ['documentVersion', 'outcome', 'assertionMode', 'spans', 'semanticAnchors'],
    'QUALITY_INVALID_EXPECTATION');
  if (!integer(expected.documentVersion) || !['valid', 'recovered'].includes(expected.outcome)
    || !['exact', 'required'].includes(expected.assertionMode)
    || !Array.isArray(expected.spans) || expected.spans.length > MAX_SPANS
    || !Array.isArray(expected.semanticAnchors) || expected.semanticAnchors.length > 64) fail('QUALITY_INVALID_EXPECTATION');
  const profile = expected.outcome === 'recovered'
    ? 'conversion-recovery-empty-external-classpath' : 'canonical-core-empty-external-classpath';
  if (fixture.compilerProfile !== profile) fail('QUALITY_INVALID_EXPECTATION');
  const boundaries = byteBoundaries(bytes);
  for (const span of expected.spans) {
    expectedRange(bytes, boundaries, span, ['startByte', 'endByte', 'category', 'text']);
    if (!CATEGORIES.includes(span.category)) fail('QUALITY_INVALID_EXPECTATION');
  }
  normalizeSyntaxSpans(fixture.source, expected.spans.map(({ startByte, endByte, category }) => ({ startByte, endByte, category })));
  if (expected.spans.length === 0 && (expected.assertionMode !== 'exact' || fixture.source.trim() !== '')) {
    fail('QUALITY_INVALID_EXPECTATION');
  }
  for (const anchor of expected.semanticAnchors) {
    expectedRange(bytes, boundaries, anchor, ['startByte', 'endByte', 'text', 'role']);
    if (!['class-declaration', 'method-declaration', 'parameter-declaration', 'parameter-reference'].includes(anchor.role)) {
      fail('QUALITY_INVALID_EXPECTATION');
    }
  }
}

/** Checks a declared six-category projection, not engine execution or semantic-token completeness. */
export function verifySyntaxObservation(fixture, observed) {
  validateFixture(fixture);
  object(observed, ['fixtureId', 'sourceSha256', 'documentVersion', 'completed', 'outcome', 'spans'],
    'QUALITY_INVALID_OBSERVATION');
  if (observed.fixtureId !== fixture.id || observed.sourceSha256 !== fixture.sourceSha256) fail('QUALITY_STALE_SOURCE');
  if (observed.documentVersion !== fixture.expectation.documentVersion) fail('QUALITY_STALE_VERSION');
  if (observed.completed !== true) fail('QUALITY_INCOMPLETE');
  if (observed.outcome !== fixture.expectation.outcome) fail('QUALITY_OUTCOME_MISMATCH');
  const actual = normalizeSyntaxSpans(fixture.source, observed.spans);
  const expected = normalizeSyntaxSpans(fixture.source,
    fixture.expectation.spans.map(({ startByte, endByte, category }) => ({ startByte, endByte, category })));
  if (fixture.expectation.assertionMode === 'exact') {
    if (!equal(actual, expected)) fail('QUALITY_SPAN_MISMATCH');
  } else {
    const actualKeys = new Set(actual.map(span => JSON.stringify(span)));
    if (expected.some(span => !actualKeys.has(JSON.stringify(span)))) fail('QUALITY_SPAN_MISMATCH');
  }
  return { assertionMode: fixture.expectation.assertionMode, checkedSpans: expected.length,
    observedSpans: actual.length, exactLexicalSpansChecked: fixture.expectation.assertionMode === 'exact',
    semanticChecked: false };
}

/** Visits scalar boundaries using constant auxiliary memory; only LF advances tree-sitter's row. */
function walkBoundaries(source, visit) {
  validateSource(source);
  let byte = 0;
  let line = 0;
  let character = 0;
  let row = 0;
  let column = 0;
  let pendingCr = false;
  if (visit(byte, line, character, true, row, column)) return;
  for (let index = 0; index < source.length;) {
    const point = source.codePointAt(index);
    const units = point > 0xffff ? 2 : 1;
    const width = point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
    if (point === 13 && source.charCodeAt(index + 1) === 10) pendingCr = true;
    else if (point === 13 || point === 10) {
      line += 1;
      character = 0;
      pendingCr = false;
    } else character += units;
    if (point === 10) { row += 1; column = 0; } else column += width;
    byte += width;
    index += units;
    if (visit(byte, line, character, !pendingCr, row, column)) return;
  }
}

export function byteOffsetToUtf16Position(source, offset) {
  if (!integer(offset)) fail('QUALITY_INVALID_BYTE_OFFSET');
  let result;
  walkBoundaries(source, (byte, line, character, representable) => {
    if (byte !== offset) return false;
    if (!representable) fail('QUALITY_UNREPRESENTABLE_LINE_ENDING');
    result = { line, character };
    return true;
  });
  if (!result) fail('QUALITY_INVALID_BYTE_OFFSET');
  return result;
}

export function utf16PositionToByteOffset(source, position) {
  object(position, ['line', 'character'], 'QUALITY_INVALID_UTF16_POSITION');
  if (!integer(position.line) || !integer(position.character)) fail('QUALITY_INVALID_UTF16_POSITION');
  let result;
  walkBoundaries(source, (byte, line, character, representable) => {
    if (!representable || position.line !== line || position.character !== character) return false;
    result = byte;
    return true;
  });
  if (result === undefined) fail('QUALITY_INVALID_UTF16_POSITION');
  return result;
}

export function byteOffsetToTreeSitterPoint(source, offset) {
  if (!integer(offset)) fail('QUALITY_INVALID_BYTE_OFFSET');
  let result;
  walkBoundaries(source, (byte, _line, _character, _representable, row, column) => {
    if (byte !== offset) return false;
    result = { row, column };
    return true;
  });
  if (!result) fail('QUALITY_INVALID_BYTE_OFFSET');
  return result;
}

/** Applies a hash/version-bound byte edit as data; no parser, compiler or source execution is involved. */
export function applyByteEdit(state, edit) {
  object(state, ['source', 'sourceSha256', 'documentVersion'], 'QUALITY_INVALID_EDIT');
  object(edit, ['fromVersion', 'toVersion', 'startByte', 'endByte', 'replacement', 'sourceSha256'], 'QUALITY_INVALID_EDIT');
  if (!integer(state.documentVersion) || !integer(edit.fromVersion) || !integer(edit.toVersion)
    || edit.toVersion <= edit.fromVersion) fail('QUALITY_INVALID_EDIT');
  if (state.documentVersion !== edit.fromVersion) fail('QUALITY_STALE_VERSION');
  const bytes = sourceBytes(state.source);
  if (!digest(state.sourceSha256) || sourceSha256(state.source) !== state.sourceSha256) fail('QUALITY_SOURCE_IDENTITY');
  if (edit.sourceSha256 !== state.sourceSha256) fail('QUALITY_STALE_SOURCE');
  const boundaries = byteBoundaries(bytes);
  if (!integer(edit.startByte) || !integer(edit.endByte) || edit.endByte < edit.startByte
    || edit.endByte > bytes.length || !boundaries[edit.startByte] || !boundaries[edit.endByte]) {
    fail('QUALITY_INVALID_EDIT_RANGE');
  }
  const replacement = sourceBytes(edit.replacement);
  if (bytes.length - (edit.endByte - edit.startByte) + replacement.length > MAX_BYTES) fail('QUALITY_SIZE_LIMIT');
  const next = Buffer.concat([bytes.subarray(0, edit.startByte), replacement, bytes.subarray(edit.endByte)]).toString('utf8');
  if (next === state.source) fail('QUALITY_NO_OP_EDIT');
  return { source: next, sourceSha256: sourceSha256(next), documentVersion: edit.toVersion };
}

export function validateQualitySuite(suite) {
  object(suite, ['schemaVersion', 'id', 'categories', 'fixtures', 'traces'], 'QUALITY_INVALID_SUITE');
  if (suite.schemaVersion !== 1 || suite.id !== 'gvy-authored-quality-v1' || !equal(suite.categories, CATEGORIES)
    || !Array.isArray(suite.fixtures) || suite.fixtures.length !== FIXTURES.length
    || !Array.isArray(suite.traces) || suite.traces.length !== TRACES.length) fail('QUALITY_INVALID_SUITE');
  for (const fixture of suite.fixtures) validateFixture(fixture);
  if (!equal(suite.fixtures.map(fixture => fixture.id).sort(), FIXTURES)) fail('QUALITY_INVALID_SUITE');
  const fixtures = new Map(suite.fixtures.map(fixture => [fixture.id, fixture]));
  for (const trace of suite.traces) {
    object(trace, ['id', 'initialFixtureId', 'edit', 'expectedSource', 'expectedSourceSha256'], 'QUALITY_INVALID_SUITE');
    if (!TRACES.includes(trace.id) || !fixtures.has(trace.initialFixtureId)) fail('QUALITY_INVALID_SUITE');
    if (!digest(trace.expectedSourceSha256) || sourceSha256(trace.expectedSource) !== trace.expectedSourceSha256) {
      fail('QUALITY_SOURCE_IDENTITY');
    }
    const fixture = fixtures.get(trace.initialFixtureId);
    const result = applyByteEdit({ source: fixture.source, sourceSha256: fixture.sourceSha256,
      documentVersion: fixture.expectation.documentVersion }, trace.edit);
    if (result.source !== trace.expectedSource || result.sourceSha256 !== trace.expectedSourceSha256) fail('QUALITY_TRACE_MISMATCH');
  }
  if (!equal(suite.traces.map(trace => trace.id).sort(), TRACES)) fail('QUALITY_INVALID_SUITE');
  return { fixtures: suite.fixtures.length, traces: suite.traces.length,
    exactFixtures: suite.fixtures.filter(fixture => fixture.expectation.assertionMode === 'exact').length,
    requiredOnlyFixtures: suite.fixtures.filter(fixture => fixture.expectation.assertionMode === 'required').length,
    compilerCompatibility: 'not-executed', semanticAnchors: 'metadata-only' };
}
