// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { sha256 } from './source-files.mjs';

const fail = () => { throw new Error('FEASIBILITY_PRIMARY_RESULT_INVALID'); };
const keys = (value, expected) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).sort().join(',') === expected;
const boundary = (bytes, offset) => Number.isSafeInteger(offset) && offset >= 0 && offset <= bytes.length && (offset === bytes.length || (bytes[offset] & 0xc0) !== 0x80);
const canonical = value => JSON.stringify(value, (_, item) => item && typeof item === 'object' && !Array.isArray(item) ? Object.fromEntries(Object.entries(item).sort(([a], [b]) => a.localeCompare(b))) : item);

/** Check B01 byte coverage, predicate anchors and recovery location, not complete lexical or semantic parity. */
export function validatePrimaryResult(native, fixtures, expected, digests) {
  if (!keys(fixtures, 'malformed,valid') || !expected || native.grammarAbi !== expected.grammarAbi || canonical(native.limitations) !== canonical(expected.limitations)) fail();
  const bytes = {};
  for (const [name, source] of Object.entries(fixtures)) {
    if (typeof source !== 'string' || source.length > 1024) fail();
    bytes[name] = Buffer.from(source);
    if (bytes[name].length > 1024 || sha256(bytes[name]) !== digests[name]) fail();
  }
  if (!Array.isArray(native.validSpans) || native.validSpans.length === 0 || native.validSpans.length > 8192) fail();
  let end = 0;
  for (const span of native.validSpans) {
    if (!keys(span, 'category,endByte,startByte') || span.startByte !== end || !boundary(bytes.valid, span.startByte) || !boundary(bytes.valid, span.endByte) || span.endByte <= span.startByte || (span.category !== null && (typeof span.category !== 'string' || !/^[a-z][a-z0-9_.-]{0,127}$/.test(span.category)))) fail();
    end = span.endByte;
  }
  if (end !== bytes.valid.length) fail();
  for (const [word, category] of [['int', 'type.builtin'], ['String', 'type']]) {
    const start = bytes.valid.indexOf(word);
    if (start < 0 || !native.validSpans.some(span => span.startByte === start && span.endByte === start + Buffer.byteLength(word) && span.category === category)) fail();
  }
  if (!Array.isArray(native.malformedIssues) || native.malformedIssues.length === 0 || native.malformedIssues.length > 256) fail();
  const delimiter = bytes.malformed.indexOf('(');
  const ends = [bytes.malformed.length, Buffer.byteLength(fixtures.malformed.trimEnd())];
  let found = false;
  for (const issue of native.malformedIssues) {
    if (!keys(issue, 'endByte,kind,startByte') || !['ERROR', 'MISSING'].includes(issue.kind) || !boundary(bytes.malformed, issue.startByte) || !boundary(bytes.malformed, issue.endByte) || (issue.kind === 'ERROR' ? issue.endByte <= issue.startByte : issue.endByte !== issue.startByte)) fail();
    if ((issue.kind === 'ERROR' && delimiter >= 0 && issue.startByte <= delimiter && delimiter < issue.endByte) || (issue.kind === 'MISSING' && ends.includes(issue.startByte))) found = true;
  }
  if (!found) fail();
}
