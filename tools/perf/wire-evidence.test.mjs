import assert from 'node:assert/strict';
import test from 'node:test';
import { inspectWirePrefix } from './wire-evidence.mjs';

const frame = body => {
  const bytes = typeof body === 'string' ? Buffer.from(body) : body;
  return Buffer.concat([Buffer.from('Content-Length: ' + bytes.length + '\r\n\r\n'), bytes]);
};
const inspect = (bytes, limits) => inspectWirePrefix(bytes.toString('base64'), limits);
const field = (message, name) => message.fields.find(entry => entry.name === name)?.rawJson;

test('original UTF8 bytes and large numeric ID lexemes survive inspection', () => {
  const body = '{"jsonrpc":"2.0","id":9007199254740993,"result":{"left":"é😀"}}';
  const original = frame(body);
  const result = inspect(original);
  assert.equal(result.boundary, 'aligned');
  assert.equal(result.prefixBase64, original.toString('base64'));
  assert.equal(result.consumedBytes, original.length);
  assert.equal(Buffer.from(result.frames[0].bodyBase64, 'base64').toString(), body);
  assert.deepEqual(result.frames[0].id, { kind: 'number', rawJson: '9007199254740993' });
  assert.equal(field(result.frames[0], 'result'), '{"left":"é😀"}');
});

test('missing and explicit null remain different and arbitrary union keys are not projected', () => {
  const bodies = [
    '{"jsonrpc":"2.0","method":"custom/note"}',
    '{"jsonrpc":"2.0","method":"custom/note","params":null}',
    '{"jsonrpc":"2.0","id":null,"result":{"right":[null]}}',
    '{"jsonrpc":"2.0","id":"1","error":{"code":-32601,"message":"missing"}}',
  ];
  const result = inspect(Buffer.concat(bodies.map(frame)));
  assert.equal(field(result.frames[0], 'params'), undefined);
  assert.equal(field(result.frames[1], 'params'), 'null');
  assert.deepEqual(result.frames[2].id, { kind: 'null', rawJson: 'null' });
  assert.equal(field(result.frames[2], 'result'), '{"right":[null]}');
  assert.equal(field(result.frames[3], 'error'), '{"code":-32601,"message":"missing"}');
});

test('ambiguous duplicate fields retain every lexeme rather than overwriting', () => {
  const body = '{"jsonrpc":"2.0","id":1,"i\\u0064":2,"result":null}';
  const result = inspect(frame(body));
  assert.equal(result.frames[0].bodyIssue, 'DUPLICATE_FIELD');
  assert.deepEqual(result.frames[0].fields.filter(entry => entry.name === 'id').map(entry => entry.rawJson), ['1', '2']);
  assert.equal(Buffer.from(result.frames[0].bodyBase64, 'base64').toString(), body);
});

test('an envelope containing both result and error stays visibly invalid', () => {
  const message = inspect(frame('{"jsonrpc":"2.0","id":1,"result":null,"error":null}')).frames[0];
  assert.equal(message.bodyIssue, 'MIXED_RESPONSE');
  assert.equal(field(message, 'result'), 'null');
  assert.equal(field(message, 'error'), 'null');
});

test('partial trailing frames never become completed observations', () => {
  const first = frame('{"jsonrpc":"2.0","id":1,"result":null}');
  const second = frame('{"jsonrpc":"2.0","id":2,"result":"answer"}');
  for (const cut of [1, 12, second.length - 1]) {
    const result = inspect(Buffer.concat([first, second.subarray(0, cut)]));
    assert.equal(result.frames.length, 1);
    assert.equal(result.boundary, 'partial');
    assert.equal(result.consumedBytes, first.length);
  }
});

test('ambiguous lengths and non-ASCII headers stop without guessed resynchronization', () => {
  for (const header of [
    'Content-Length: 2\r\nContent-Length: 2',
    'Content-Length: -2',
    'Content-Length: 1.5',
    'Content-Length: 999999999999999999999999',
    'Content-Léngth: 2',
  ]) {
    const result = inspect(Buffer.from(header + '\r\n\r\n{}'));
    assert.equal(result.boundary, 'invalid');
    assert.equal(result.frames.length, 0);
    assert.equal(result.consumedBytes, 0);
  }
});

test('UTF8 and malformed JSON failures preserve the original body and the next frame', () => {
  const good = frame('{"jsonrpc":"2.0","id":2,"result":null}');
  for (const [bad, issue] of [[Buffer.from([0xff]), 'INVALID_UTF8'], [Buffer.from('{bad}'), 'INVALID_JSON']]) {
    const result = inspect(Buffer.concat([frame(bad), good]));
    assert.equal(result.boundary, 'aligned');
    assert.equal(result.frames[0].bodyIssue, issue);
    assert.equal(result.frames[0].bodyBase64, bad.toString('base64'));
    assert.equal(result.frames[1].bodyIssue, null);
  }
});

test('frame body and header limits preserve a limited outcome', () => {
  const bytes = Buffer.concat([frame('{"id":1}'), frame('{"id":2}')]);
  assert.equal(inspect(bytes, { maxFrames: 1 }).boundary, 'limited');
  assert.equal(inspect(bytes, { maxBodyBytes: 2 }).boundary, 'limited');
  assert.equal(inspect(bytes, { maxHeaderBytes: 8 }).boundary, 'limited');
  assert.equal(inspect(bytes, { maxFrames: 1 }).prefixBase64, bytes.toString('base64'));
});

test('depth and root-field bounds never certify uninspected bodies', () => {
  const depth = inspect(frame('{"id":1,"result":[[[[null]]]]}'), { maxJsonDepth: 3 });
  assert.equal(depth.frames[0].bodyIssue, 'JSON_DEPTH_LIMIT');
  const fields = inspect(frame('{"id":1,"result":null,"extra":true}'), { maxRootFields: 2 });
  assert.equal(fields.frames[0].bodyIssue, 'ROOT_FIELD_LIMIT');
});

test('braces and escaped quotes inside strings do not change structural depth', () => {
  const body = '{"id":"x","result":"[{\\"quoted\\"}]]"}';
  const result = inspect(frame(body), { maxJsonDepth: 1 });
  assert.equal(result.frames[0].bodyIssue, null);
  assert.equal(field(result.frames[0], 'result'), '"[{\\"quoted\\"}]]"');
});

test('empty and aligned prefixes make no stream-completion or verification claim', () => {
  const empty = inspect(Buffer.alloc(0));
  assert.equal(empty.boundary, 'aligned');
  assert.deepEqual(empty.frames, []);
  assert.equal(Object.hasOwn(empty, 'verified'), false);
  assert.equal(Object.hasOwn(empty, 'streamComplete'), false);
});

test('invalid export or option input fails with a fixed non-sensitive error', () => {
  for (const value of ['!', 'Zg', 'Zh==', 'private-secret-canary']) {
    assert.throws(() => inspectWirePrefix(value), { message: 'INVALID_WIRE_EVIDENCE' });
  }
  for (const limits of [{ maxBytes: 0 }, { maxBytes: 2 ** 30 }, { typo: 1 }]) {
    assert.throws(() => inspect(Buffer.alloc(0), limits), { message: 'INVALID_WIRE_EVIDENCE' });
  }
  let invoked = false;
  assert.throws(() => inspect(Buffer.alloc(0), { get maxBytes() { invoked = true; return 1; } }));
  assert.equal(invoked, false);
});

test('returned byte evidence and field metadata are immutable', () => {
  const result = inspect(frame('{"id":1,"result":null}'));
  assert.throws(() => result.frames.push(null), TypeError);
  assert.throws(() => { result.frames[0].fields[0].rawJson = '2'; }, TypeError);
  assert.throws(() => { result.prefixBase64 = ''; }, TypeError);
});

test('uninspected or ambiguous fields never become trusted missing IDs or message shapes', () => {
  for (const body of ['{"method":"m","id":1}', '{"id":1,"result":null}', '{"id":1,"method":"m"}']) {
    const message = inspect(frame(body), { maxRootFields: 1 }).frames[0];
    assert.equal(message.bodyIssue, 'ROOT_FIELD_LIMIT');
    assert.equal(message.shape, 'unknown');
    assert.deepEqual(message.id, { kind: 'unknown', rawJson: null });
  }
  const duplicate = inspect(frame('{"id":1,"id":2,"result":null}')).frames[0];
  assert.equal(duplicate.shape, 'unknown');
  assert.equal(duplicate.id.kind, 'unknown');
});

test('hostile option proxies cannot disclose trap errors or execute traps', () => {
  let invoked = false;
  const options = new Proxy({}, {
    getPrototypeOf() { invoked = true; throw new Error('private-canary'); },
  });
  assert.throws(() => inspect(Buffer.alloc(0), options), { message: 'INVALID_WIRE_EVIDENCE' });
  assert.equal(invoked, false);
});

test('control bytes in header values are invalid rather than silently accepted', () => {
  const body = '{"id":1,"result":null}';
  const header = Buffer.from('Content-Length: ' + Buffer.byteLength(body) + '\r\nX-Trace: bad\0value\r\n\r\n');
  const result = inspect(Buffer.concat([header, Buffer.from(body)]));
  assert.equal(result.boundary, 'invalid');
  assert.equal(result.frames.length, 0);
});
