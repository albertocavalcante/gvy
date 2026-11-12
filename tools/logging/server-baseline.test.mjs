import assert from 'node:assert/strict';
import { PassThrough } from 'node:stream';
import { test } from 'node:test';
import { createHash } from 'node:crypto';
import { FrameParser, PhysicalLineCounter, RpcPeer } from './lsp-protocol.mjs';

const frame = (value) => {
  const body = Buffer.from(JSON.stringify(value));
  return Buffer.concat([Buffer.from(`Content-Length: ${body.length}\r\n\r\n`), body]);
};

test('frames split UTF-8 and multiple responses with strict byte limits', () => {
  const messages = [];
  const parser = new FrameParser((m) => messages.push(m), { headerBytes: 128, bodyBytes: 1024 });
  const bytes = Buffer.concat([frame({ jsonrpc: '2.0', id: 1, result: 'é🧪' }), frame({ jsonrpc: '2.0', id: 2, result: null })]);
  for (const byte of bytes) parser.write(Buffer.from([byte]));
  parser.end();
  assert.deepEqual(messages.map((m) => m.id), [1, 2]);
  assert.equal(messages[0].result, 'é🧪');
  assert.throws(() => new FrameParser(() => {}, { headerBytes: 8, bodyBytes: 8 }).write(Buffer.from('a'.repeat(9))), /header/i);
  assert.throws(() => new FrameParser(() => {}, { headerBytes: 128, bodyBytes: 8 }).write(Buffer.from('Content-Length: 9\r\n\r\n')), /body/i);
  assert.throws(() => new FrameParser(() => {}).write(Buffer.from('Content-Length: 1\r\nContent-Length: 1\r\n\r\n')), /length/i);
  assert.throws(() => new FrameParser(() => {}).write(Buffer.from('Content-Length: 1\r\ninvalid-header\r\n\r\n')), /header/i);
});

test('reports incomplete EOF and counts physical lines without retaining data', () => {
  const parser = new FrameParser(() => {});
  parser.write(Buffer.from('Content-Length: 9\r\n\r\nabc'));
  assert.throws(() => parser.end(), /incomplete/i);
  const bytes = Buffer.from('é\r\nlast');
  const counter = new PhysicalLineCounter();
  for (const byte of bytes) counter.write(Buffer.from([byte]));
  assert.deepEqual(counter.finish(), { bytes: bytes.length, physicalLines: 2, sha256: createHash('sha256').update(bytes).digest('hex') });
});

test('registers requests before writes, bounds pending and rejects timeout or EOF', async () => {
  const output = new PassThrough();
  const peer = new RpcPeer(output, { deadlineMs: 25, maxPending: 1 });
  output.on('data', () => peer.receive({ jsonrpc: '2.0', id: 1, result: 'ok' }));
  assert.equal(await peer.request('first', {}), 'ok');
  output.removeAllListeners('data');
  const pending = peer.request('second', {});
  await assert.rejects(peer.request('third', {}), /pending/i);
  await assert.rejects(pending, /timed out/i);
  const eof = peer.request('fourth', {});
  peer.close(new Error('EOF'));
  await assert.rejects(eof, /EOF/);
});

test('answers workspace configuration requests with one setting per item', () => {
  const output = new PassThrough();
  const received = [];
  const parser = new FrameParser((m) => received.push(m));
  output.on('data', (chunk) => parser.write(chunk));
  const peer = new RpcPeer(output, { configuration: { 'groovy.server.logLevel': 'debug' } });
  peer.receive({ jsonrpc: '2.0', id: 17, method: 'workspace/configuration', params: { items: [{ section: 'groovy.server.logLevel' }, { section: 'unknown' }] } });
  assert.deepEqual(received, [{ jsonrpc: '2.0', id: 17, result: ['debug', null] }]);
  peer.receive({ jsonrpc: '2.0', id: 18, method: 'workspace/configuration', params: { items: Array.from({ length: 65 }, () => ({ section: 'groovy' })) } });
  assert.equal(received[1].error.code, -32602);
});

test('session handles server requests and shuts down cleanly with unavailable terminal diagnostics', async () => {
  const { runSession } = await import('./server-baseline.mjs');
  const { EventEmitter } = await import('node:events');
  const child = Object.assign(new EventEmitter(), { stdin: new PassThrough(), stdout: new PassThrough(), stderr: new PassThrough(), kill: () => {} });
  const observed = [];
  const incoming = new FrameParser((m) => {
    observed.push(m.method);
    if (m.method === 'initialize') {
      child.stdout.write(frame({ jsonrpc: '2.0', id: 999, method: 'workspace/configuration', params: { items: [{ section: 'groovy.server.logLevel' }] } }));
      child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: { capabilities: {} } }));
    } else if (m.method === 'textDocument/hover') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: null }));
    else if (m.method === 'textDocument/completion') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: [] }));
    else if (m.method === 'groovy/awaitDiagnostics') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, error: { code: -32601, message: 'missing' } }));
    else if (m.method === 'shutdown') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: null }));
    else if (m.method === 'exit') { child.stdout.end(); child.stderr.end('ok\n'); setImmediate(() => child.emit('close', 0, null)); }
  });
  child.stdin.on('data', (chunk) => incoming.write(chunk));
  const result = await runSession(child, { uri: 'file:///synthetic/Sample.groovy', text: 'class Sample {}\n', level: 'debug', warmups: 1, samples: 2, deadlineMs: 1000 });
  assert.equal(result.status, 'complete');
  assert.equal(result.diagnostics.terminal, 'unavailable');
  assert.equal(result.samples.length, 2);
  assert.equal(result.samples[0].hover.kind, 'returned-no-result');
  assert.equal(result.samples[0].completion.kind, 'empty-list');
  assert.equal(result.requestSpans.hover['returned-no-result'].n, 2);
  assert.equal(result.requestSpans.completion['empty-list'].n, 2);
  assert.ok(observed.includes('initialized') && observed.includes('textDocument/didChange') && observed.includes('shutdown') && observed.includes('exit'));
  assert.equal(result.stderr.physicalLines, 1);
});

test('session censors timeout and reaps a child that ignores graceful exit', async () => {
  const { runSession } = await import('./server-baseline.mjs');
  const { EventEmitter } = await import('node:events');
  const signals = [];
  const child = Object.assign(new EventEmitter(), { stdin: new PassThrough(), stdout: new PassThrough(), stderr: new PassThrough(), kill: (signal) => { signals.push(signal); child.stdout.end(); child.stderr.end(); setImmediate(() => child.emit('close', null, signal)); } });
  const result = await runSession(child, { uri: 'file:///synthetic/a.groovy', text: 'class A {}', level: 'info', warmups: 0, samples: 1, deadlineMs: 30 });
  assert.equal(result.status, 'incomplete');
  assert.equal(result.samples.length, 0);
  assert.deepEqual(signals, ['SIGTERM']);
});

test('terminal provider failures are not success and first publication is not a terminal barrier', async () => {
  const { runSession } = await import('./server-baseline.mjs');
  const { EventEmitter } = await import('node:events');
  const child = Object.assign(new EventEmitter(), { stdin: new PassThrough(), stdout: new PassThrough(), stderr: new PassThrough(), kill: () => {} });
  const uri = 'file:///synthetic/Sample.groovy';
  const incoming = new FrameParser((m) => {
    if (m.method === 'initialize') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: { capabilities: {} } }));
    else if (m.method === 'textDocument/didChange') child.stdout.write(frame({ jsonrpc: '2.0', method: 'textDocument/publishDiagnostics', params: { uri, version: m.params.textDocument.version, diagnostics: [{ message: 'secret detail' }] } }));
    else if (m.method === 'textDocument/hover') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: null }));
    else if (m.method === 'textDocument/completion') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: [] }));
    else if (m.method === 'groovy/awaitDiagnostics') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: { uri, version: m.params.version, runId: 1, configurationGeneration: 1, complete: true, diagnostics: [], providerIds: ['test'], failures: [{ message: 'secret detail' }] } }));
    else if (m.method === 'shutdown') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: null }));
    else if (m.method === 'exit') { child.stdout.end(); child.stderr.end(); setImmediate(() => child.emit('close', 0, null)); }
  });
  child.stdin.on('data', (chunk) => incoming.write(chunk));
  const result = await runSession(child, { uri, text: 'class A {}', level: 'info', warmups: 0, samples: 1, deadlineMs: 1000 });
  assert.equal(result.status, 'complete-with-provider-failures');
  assert.equal(result.samples[0].status, 'provider-failures');
  assert.equal(result.samples[0].firstPublication.diagnosticCount, 1);
  assert.equal(result.samples[0].terminal.failureCount, 1);
  assert.ok(!JSON.stringify(result).includes('secret detail'));
});

test('early EOF before initialize response is censored despite exit zero', async () => {
  const { runSession } = await import('./server-baseline.mjs');
  const { EventEmitter } = await import('node:events');
  const child = Object.assign(new EventEmitter(), { stdin: new PassThrough(), stdout: new PassThrough(), stderr: new PassThrough(), kill: () => {} });
  child.stdin.once('data', () => { child.stdout.end(); child.stderr.end(); setImmediate(() => child.emit('close', 0, null)); });
  const result = await runSession(child, { uri: 'file:///synthetic/a.groovy', text: 'class A {}', level: 'info', warmups: 0, samples: 1, deadlineMs: 1000 });
  assert.equal(result.status, 'incomplete');
  assert.equal(result.failureStage, 'initialize');
  assert.equal(result.samples.length, 0);
  assert.equal(result.childReaped, true);
});

test('nonconforming empty shutdown object is reported and still followed by exit', async () => {
  const { runSession } = await import('./server-baseline.mjs');
  const { EventEmitter } = await import('node:events');
  const child = Object.assign(new EventEmitter(), { stdin: new PassThrough(), stdout: new PassThrough(), stderr: new PassThrough(), kill: () => { throw new Error('unexpected kill'); } });
  let sawExit = false;
  const incoming = new FrameParser((m) => {
    if (m.method === 'initialize') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: { capabilities: {} } }));
    else if (m.method === 'textDocument/hover') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: null }));
    else if (m.method === 'textDocument/completion') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: [] }));
    else if (m.method === 'groovy/awaitDiagnostics') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, error: { code: -32601 } }));
    else if (m.method === 'shutdown') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: {} }));
    else if (m.method === 'exit') { sawExit = true; child.stdout.end(); child.stderr.end(); setImmediate(() => child.emit('close', 0, null)); }
  });
  child.stdin.on('data', (chunk) => incoming.write(chunk));
  const result = await runSession(child, { uri: 'file:///synthetic/a.groovy', text: 'class A {}', level: 'info', warmups: 0, samples: 1, deadlineMs: 1000 });
  assert.equal(result.status, 'complete-with-protocol-deviation');
  assert.equal(result.shutdownResultType, 'object');
  assert.equal(result.shutdownConformance, 'nonstandard-empty-object');
  assert.equal(sawExit, true);
  assert.equal(result.childReaped, true);
});

test('terminal errors distinguish obsolete from invalid parameters without error text', async () => {
  const { runSession } = await import('./server-baseline.mjs');
  const { EventEmitter } = await import('node:events');
  for (const code of [-32801, -32602]) {
    const child = Object.assign(new EventEmitter(), { stdin: new PassThrough(), stdout: new PassThrough(), stderr: new PassThrough(), kill: (signal) => { child.stdout.end(); child.stderr.end(); setImmediate(() => child.emit('close', null, signal)); } });
    const incoming = new FrameParser((m) => {
      if (m.method === 'initialize') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: { capabilities: {} } }));
      else if (m.method === 'textDocument/hover') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: null }));
      else if (m.method === 'textDocument/completion') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: [] }));
      else if (m.method === 'groovy/awaitDiagnostics') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, error: { code, message: 'secret error text' } }));
    });
    child.stdin.on('data', (chunk) => incoming.write(chunk));
    const result = await runSession(child, { uri: 'file:///synthetic/a.groovy', text: 'class A {}', level: 'info', warmups: 0, samples: 1, deadlineMs: 1000 });
    assert.equal(result.status, 'incomplete');
    assert.equal(result.failureStage, 'groovy/awaitDiagnostics');
    assert.equal(result.failureCode, code);
    assert.equal(result.samples[0].status, code === -32801 ? 'obsolete' : 'incomplete');
    assert.ok(!JSON.stringify(result).includes('secret error text'));
  }
});

test('invalid completion is censored even when the server responds', async () => {
  const { runSession } = await import('./server-baseline.mjs');
  const { EventEmitter } = await import('node:events');
  const child = Object.assign(new EventEmitter(), { stdin: new PassThrough(), stdout: new PassThrough(), stderr: new PassThrough(), kill: (signal) => { child.stdout.end(); child.stderr.end(); setImmediate(() => child.emit('close', null, signal)); } });
  const incoming = new FrameParser((m) => {
    if (m.method === 'initialize') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: { capabilities: {} } }));
    else if (m.method === 'textDocument/hover') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: null }));
    else if (m.method === 'textDocument/completion') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: { items: [{ label: 'x' }] } }));
  });
  child.stdin.on('data', (chunk) => incoming.write(chunk));
  const result = await runSession(child, { uri: 'file:///synthetic/a.groovy', text: 'class A {}', level: 'info', warmups: 0, samples: 1, deadlineMs: 1000 });
  assert.equal(result.status, 'incomplete');
  assert.equal(result.failureStage, 'textDocument/completion');
  assert.equal(result.samples[0].status, 'incomplete');
  assert.equal(result.requestSpans.completion['returned-no-result'].n, 0);
});

test('groups null and present results separately without retaining response content', async () => {
  const { runSession } = await import('./server-baseline.mjs');
  const { EventEmitter } = await import('node:events');
  const child = Object.assign(new EventEmitter(), { stdin: new PassThrough(), stdout: new PassThrough(), stderr: new PassThrough(), kill: () => {} });
  let iteration = 0;
  const incoming = new FrameParser((m) => {
    if (m.method === 'initialize') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: { capabilities: {} } }));
    else if (m.method === 'textDocument/didChange') iteration++;
    else if (m.method === 'textDocument/hover') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: iteration === 1 ? null : { contents: { kind: 'markdown', value: 'secret hover' } } }));
    else if (m.method === 'textDocument/completion') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: iteration === 1 ? [] : { isIncomplete: false, items: [{ label: 'secret label' }] } }));
    else if (m.method === 'groovy/awaitDiagnostics') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, error: { code: -32601 } }));
    else if (m.method === 'shutdown') child.stdout.write(frame({ jsonrpc: '2.0', id: m.id, result: null }));
    else if (m.method === 'exit') { child.stdout.end(); child.stderr.end(); setImmediate(() => child.emit('close', 0, null)); }
  });
  child.stdin.on('data', (chunk) => incoming.write(chunk));
  const result = await runSession(child, { uri: 'file:///synthetic/a.groovy', text: 'class A {}', level: 'info', warmups: 0, samples: 2, deadlineMs: 1000 });
  assert.equal(result.status, 'complete');
  assert.equal(result.requestSpans.hover['returned-no-result'].n, 1);
  assert.equal(result.requestSpans.hover['content-present'].n, 1);
  assert.equal(result.requestSpans.completion['empty-list'].n, 1);
  assert.equal(result.requestSpans.completion['items-present'].n, 1);
  assert.equal(result.samples[1].completion.count, 1);
  assert.ok(!JSON.stringify(result).includes('secret'));
});

test('escalates a timed-out child from TERM to KILL and observes close', async () => {
  const { runSession } = await import('./server-baseline.mjs');
  const { EventEmitter } = await import('node:events');
  const signals = [];
  const child = Object.assign(new EventEmitter(), { stdin: new PassThrough(), stdout: new PassThrough(), stderr: new PassThrough(), kill: (signal) => {
    signals.push(signal);
    if (signal === 'SIGKILL') { child.stdout.end(); child.stderr.end(); setImmediate(() => child.emit('close', null, signal)); }
  } });
  const result = await runSession(child, { uri: 'file:///synthetic/a.groovy', text: 'class A {}', level: 'info', warmups: 0, samples: 1, deadlineMs: 20 });
  assert.equal(result.status, 'incomplete');
  assert.equal(result.childReaped, true);
  assert.equal(result.childSignal, 'SIGKILL');
  assert.deepEqual(signals, ['SIGTERM', 'SIGKILL']);
});

test('private launch profile is exclusive and does not expose Java path in receipts', async () => {
  const { writePrivateProfile } = await import('./server-baseline.mjs');
  const { mkdtempSync, readFileSync, rmSync, statSync } = await import('node:fs');
  const os = await import('node:os');
  const path = await import('node:path');
  const dir = mkdtempSync(path.join(os.tmpdir(), 'gvy-profile-test-'));
  try {
    const file = path.join(dir, 'profile.json');
    writePrivateProfile(file, '/private/java', 'openjdk version "21"');
    assert.deepEqual(JSON.parse(readFileSync(file, 'utf8')), { javaExecutable: '/private/java', javaVersion: 'openjdk version "21"' });
    if (process.platform !== 'win32') assert.equal(statSync(file).mode & 0o077, 0);
    assert.throws(() => writePrivateProfile(file, '/other/java', 'other'), /EEXIST/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
