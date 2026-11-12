// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

// Offline inspection only: this module never reads a live stream or replaces SDK framing.
// Bodies and field values remain original bytes / JSON lexemes, never rounded JS numbers.
import { types } from 'node:util';

const HARD_LIMITS = Object.freeze({
  maxBytes: 1024 * 1024,
  maxFrames: 256,
  maxHeaderBytes: 8192,
  maxBodyBytes: 1024 * 1024,
  maxJsonDepth: 128,
  maxRootFields: 64,
});
const invalid = () => { throw new Error('INVALID_WIRE_EVIDENCE'); };
const freeze = Object.freeze;
const utf8 = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
const separator = Buffer.from('\r\n\r\n');

function limitsFor(options) {
  if (options === undefined) return HARD_LIMITS;
  if (options === null || typeof options !== 'object' || types.isProxy(options) ||
      ![Object.prototype, null].includes(Object.getPrototypeOf(options))) invalid();
  const limits = { ...HARD_LIMITS };
  for (const key of Reflect.ownKeys(options)) {
    const property = Object.getOwnPropertyDescriptor(options, key);
    if (!Object.hasOwn(HARD_LIMITS, key) || !Object.hasOwn(property, 'value')) invalid();
    const value = property.value;
    if (!Number.isSafeInteger(value) || value < 1 || value > HARD_LIMITS[key]) invalid();
    limits[key] = value;
  }
  return limits;
}

function depthIssue(text, limit) {
  let depth = 0;
  for (let at = 0; at < text.length; at++) {
    if (text[at] === '"') {
      at = stringEnd(text, at) - 1;
    } else if (text[at] === '{' || text[at] === '[') {
      if (++depth > limit) return 'JSON_DEPTH_LIMIT';
    } else if (text[at] === '}' || text[at] === ']') {
      depth--;
    }
  }
  return null;
}

function stringEnd(text, start) {
  for (let at = start + 1; at < text.length; at++) {
    if (text[at] === '\\') at++;
    else if (text[at] === '"') return at + 1;
  }
  return text.length;
}

const whitespace = (text, start) => {
  let at = start;
  while (at < text.length && /[\t\n\r ]/.test(text[at])) at++;
  return at;
};

function valueEnd(text, start) {
  if (text[start] === '"') return stringEnd(text, start);
  if (text[start] !== '{' && text[start] !== '[') {
    let at = start;
    while (at < text.length && !/[,}\]\t\n\r ]/.test(text[at])) at++;
    return at;
  }
  let depth = 0;
  for (let at = start; at < text.length; at++) {
    if (text[at] === '"') at = stringEnd(text, at) - 1;
    else if (text[at] === '{' || text[at] === '[') depth++;
    else if ((text[at] === '}' || text[at] === ']') && --depth === 0) return at + 1;
  }
  return text.length;
}

function rootFields(text, limit) {
  const fields = [];
  const names = new Set();
  let duplicate = false;
  let at = whitespace(text, 0) + 1;
  while ((at = whitespace(text, at)) < text.length && text[at] !== '}') {
    if (fields.length === limit) return { fields, issue: 'ROOT_FIELD_LIMIT' };
    const end = stringEnd(text, at);
    const name = JSON.parse(text.slice(at, end));
    const start = whitespace(text, whitespace(text, end) + 1);
    const finish = valueEnd(text, start);
    fields.push(freeze({ name, rawJson: text.slice(start, finish) }));
    if (names.has(name)) duplicate = true;
    names.add(name);
    at = whitespace(text, finish);
    if (text[at] === ',') at++;
  }
  return { fields, issue: duplicate ? 'DUPLICATE_FIELD' : null };
}

function inspectBody(bytes, limits) {
  const unknownId = () => freeze({ kind: 'unknown', rawJson: null });
  const empty = issue => ({ fields: freeze([]), id: unknownId(), shape: 'unknown', bodyIssue: issue });
  let text;
  try { text = utf8.decode(bytes); } catch { return empty('INVALID_UTF8'); }
  const depth = depthIssue(text, limits.maxJsonDepth);
  if (depth) return empty(depth);
  let parsed;
  try { parsed = JSON.parse(text); } catch { return empty('INVALID_JSON'); }
  // Parsed numbers are used for neither identity, values nor comparisons.
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return empty('NON_OBJECT_BODY');
  const { fields, issue } = rootFields(text, limits.maxRootFields);
  if (issue) return { fields: freeze(fields), id: unknownId(), shape: 'unknown', bodyIssue: issue };
  const ids = fields.filter(field => field.name === 'id');
  const id = ids.length !== 1 ? { kind: ids.length ? 'invalid' : 'missing', rawJson: null } : {
    kind: ids[0].rawJson === 'null' ? 'null' : ids[0].rawJson.startsWith('"') ? 'string' :
      /^-?[0-9]/.test(ids[0].rawJson) ? 'number' : 'invalid',
    rawJson: ids[0].rawJson,
  };
  const has = name => fields.some(field => field.name === name);
  const mixed = has('result') && has('error');
  const shape = has('method') && !has('result') && !has('error') ? (ids.length ? 'request' : 'notification') :
    ids.length === 1 && (has('result') !== has('error')) && !has('method') ? 'response' : 'invalid';
  return { fields: freeze(fields), id: freeze(id), shape, bodyIssue: issue ?? (mixed ? 'MIXED_RESPONSE' : null) };
}

function headerLength(bytes) {
  if (bytes.some(byte => byte > 0x7f)) return { issue: 'NON_ASCII_HEADER' };
  if (bytes.some(byte => byte === 0x7f || (byte < 0x20 && ![0x09, 0x0a, 0x0d].includes(byte)))) {
    return { issue: 'HEADER_CONTROL_BYTE' };
  }
  let length;
  let contentType;
  for (const line of bytes.toString('ascii').split('\r\n')) {
    const match = /^([!#$%&'*+.^_a-z0-9|~-]+):[\t ]*(.*)$/i.exec(line);
    if (!match) return { issue: 'INVALID_HEADER' };
    const name = match[1].toLowerCase();
    const value = match[2].trim();
    if (name === 'content-length') {
      if (length !== undefined || !/^[0-9]+$/.test(value)) return { issue: 'INVALID_CONTENT_LENGTH' };
      length = Number(value);
      if (!Number.isSafeInteger(length)) return { issue: 'INVALID_CONTENT_LENGTH' };
    } else if (name === 'content-type') {
      if (contentType !== undefined) return { issue: 'DUPLICATE_CONTENT_TYPE' };
      contentType = value;
      const charset = /(?:^|;)\s*charset\s*=\s*([^;\s]+)/i.exec(value)?.[1].toLowerCase();
      if (charset !== undefined && !['utf-8', 'utf8'].includes(charset)) return { issue: 'UNSUPPORTED_CHARSET' };
    }
  }
  return length === undefined ? { issue: 'MISSING_CONTENT_LENGTH' } : { length };
}

/**
 * Inspect a bounded original prefix. "aligned" means a frame boundary, never EOF,
 * delivery, successful verification or full JSON-RPC/LSP method-schema conformance.
 * Every original byte remains in prefixBase64, even when inspection stops or fails.
 * A null bodyIssue means JSON/root-field inspection only; unknown envelope fields
 * and arbitrary result keys are preserved. No raw payload is logged or persisted.
 */
export function inspectWirePrefix(prefixBase64, options) {
  const limits = limitsFor(options);
  if (typeof prefixBase64 !== 'string' || prefixBase64.length > 4 * Math.ceil(limits.maxBytes / 3) ||
      !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(prefixBase64)) invalid();
  const bytes = Buffer.from(prefixBase64, 'base64');
  if (bytes.length > limits.maxBytes || bytes.toString('base64') !== prefixBase64) invalid();
  const frames = [];
  let at = 0;
  const result = (boundary, reason = null) => freeze({
    prefixBase64, byteLength: bytes.length, consumedBytes: at, boundary, reason, frames: freeze(frames),
  });
  while (at < bytes.length) {
    if (frames.length === limits.maxFrames) return result('limited', 'FRAME_LIMIT');
    const end = bytes.indexOf(separator, at);
    if (end < 0) return bytes.length - at >= limits.maxHeaderBytes ?
      result('limited', 'HEADER_LIMIT') : result('partial', 'PARTIAL_HEADER');
    if (end + separator.length - at > limits.maxHeaderBytes) return result('limited', 'HEADER_LIMIT');
    const header = bytes.subarray(at, end);
    const parsed = headerLength(header);
    if (parsed.issue) return result('invalid', parsed.issue);
    if (parsed.length > limits.maxBodyBytes) return result('limited', 'BODY_LIMIT');
    const start = end + separator.length;
    if (parsed.length > bytes.length - start) return result('partial', 'PARTIAL_BODY');
    const body = bytes.subarray(start, start + parsed.length);
    frames.push(freeze({
      headerBase64: header.toString('base64'), bodyBase64: body.toString('base64'), ...inspectBody(body, limits),
    }));
    at = start + parsed.length;
  }
  return result('aligned');
}
