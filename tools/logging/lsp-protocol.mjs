import { createHash } from 'node:crypto';
import { performance } from 'node:perf_hooks';

/** Incremental binary framing. Header and body buffers never exceed their limits. */
export class FrameParser {
  constructor(onMessage, { headerBytes = 8192, bodyBytes = 8 * 1024 * 1024 } = {}) {
    this.onMessage = onMessage;
    this.headerLimit = headerBytes;
    this.bodyLimit = bodyBytes;
    this.header = Buffer.alloc(headerBytes);
    this.headerLength = 0;
    this.body = undefined;
    this.bodyLength = 0;
  }

  write(chunk) {
    let offset = 0;
    while (offset < chunk.length) {
      if (this.body) {
        const count = Math.min(this.body.length - this.bodyLength, chunk.length - offset);
        chunk.copy(this.body, this.bodyLength, offset, offset + count);
        offset += count;
        this.bodyLength += count;
        if (this.bodyLength === this.body.length) {
          const text = new TextDecoder('utf-8', { fatal: true }).decode(this.body);
          const message = JSON.parse(text);
          this.body = undefined;
          this.bodyLength = 0;
          this.onMessage(message);
        }
      } else {
        if (this.headerLength === this.headerLimit) throw new Error('LSP header exceeds byte limit');
        this.header[this.headerLength++] = chunk[offset++];
        if (this.headerLength >= 4 && this.header.subarray(this.headerLength - 4, this.headerLength).equals(Buffer.from('\r\n\r\n'))) {
          const raw = this.header.subarray(0, this.headerLength - 4);
          if (raw.some((byte) => byte > 127)) throw new Error('LSP header is not ASCII');
          const lines = raw.toString('ascii').split('\r\n');
          if (lines.some((line) => !/^[A-Za-z0-9-]+:[\t\x20-\x7E]*$/.test(line))) throw new Error('Invalid LSP header');
          const lengths = lines.filter((line) => /^content-length\s*:/i.test(line));
          if (lengths.length !== 1 || !/^content-length:\s*\d+\s*$/i.test(lengths[0])) throw new Error('Invalid LSP content length');
          const length = Number(lengths[0].slice(lengths[0].indexOf(':') + 1).trim());
          if (!Number.isSafeInteger(length) || length <= 0 || length > this.bodyLimit) throw new Error('LSP body exceeds byte limit or is empty');
          this.headerLength = 0;
          this.body = Buffer.alloc(length);
        }
      }
    }
  }

  end() {
    if (this.headerLength || this.body) throw new Error('Incomplete LSP frame at EOF');
  }
}

/** Raw stderr byte hash and physical lines; a final unterminated line counts once. */
export class PhysicalLineCounter {
  constructor() { this.bytes = 0; this.lines = 0; this.last = undefined; this.hash = createHash('sha256'); }
  write(chunk) {
    this.bytes += chunk.length;
    this.hash.update(chunk);
    for (const byte of chunk) if (byte === 10) this.lines++;
    if (chunk.length) this.last = chunk[chunk.length - 1];
  }
  finish() {
    return { bytes: this.bytes, physicalLines: this.lines + (this.bytes > 0 && this.last !== 10 ? 1 : 0), sha256: this.hash.digest('hex') };
  }
}

export class RpcPeer {
  constructor(output, { deadlineMs = 15000, maxPending = 4, maxQueuedBytes = 256 * 1024, configuration = {}, onNotification = () => {}, onFatal = () => {} } = {}) {
    this.output = output;
    this.deadlineMs = deadlineMs;
    this.maxPending = maxPending;
    this.maxQueuedBytes = maxQueuedBytes;
    this.queuedBytes = 0;
    this.onFatal = onFatal;
    this.configuration = configuration;
    this.onNotification = onNotification;
    this.pending = new Map();
    this.nextId = 1;
    this.failure = undefined;
  }
  send(message, onWrite) {
    if (this.failure) throw this.failure;
    const json = JSON.stringify(message);
    const length = Buffer.byteLength(json);
    const header = Buffer.from(`Content-Length: ${length}\r\n\r\n`);
    const bytes = header.length + length;
    if (bytes > this.maxQueuedBytes - this.queuedBytes) {
      const error = new Error('LSP outbound queue limit exceeded');
      error.kind = 'output-saturated';
      this.close(error);
      this.onFatal(error);
      throw error;
    }
    const frame = Buffer.concat([header, Buffer.from(json)]);
    this.queuedBytes += bytes;
    let finished = false;
    const done = (error) => {
      if (finished) return;
      finished = true;
      this.queuedBytes -= bytes;
      if (error) { this.close(error); this.onFatal(error); }
      else if (onWrite) onWrite();
    };
    try { this.output.write(frame, done); } catch (error) { done(error); throw error; }
  }
  notify(method, params) { this.send({ jsonrpc: '2.0', method, params }); }
  request(method, params, timing) {
    if (this.failure) return Promise.reject(this.failure);
    if (this.pending.size >= this.maxPending) return Promise.reject(new Error('Too many pending LSP requests'));
    const id = this.nextId++;
    if (timing) timing.enqueueMs = performance.now();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`${method} timed out`)); }, this.deadlineMs);
      this.pending.set(id, { resolve, reject, timer, timing });
      try { this.send({ jsonrpc: '2.0', id, method, params }, () => { if (timing) timing.writeCompleteMs = performance.now(); }); }
      catch (error) { clearTimeout(timer); this.pending.delete(id); reject(error); }
    });
  }
  receive(message) {
    if (!message || typeof message !== 'object' || Array.isArray(message) || message.jsonrpc !== '2.0') throw new Error('Invalid JSON-RPC message');
    if (typeof message.method === 'string') {
      if (message.id !== undefined) {
        if (message.method === 'workspace/configuration') {
          const items = message.params?.items;
          if (!Array.isArray(items) || items.length > 64 || items.some((item) => !item || typeof item !== 'object' || (item.section !== undefined && typeof item.section !== 'string'))) {
            this.send({ jsonrpc: '2.0', id: message.id, error: { code: -32602, message: 'Invalid parameters' } });
          } else {
            this.send({ jsonrpc: '2.0', id: message.id, result: items.map((item) => Object.hasOwn(this.configuration, item.section) ? this.configuration[item.section] : null) });
          }
        } else {
          this.send({ jsonrpc: '2.0', id: message.id, error: { code: -32601, message: 'Method not found' } });
        }
      } else this.onNotification(message);
      return;
    }
    const entry = this.pending.get(message?.id);
    if (!entry) return;
    this.pending.delete(message.id);
    clearTimeout(entry.timer);
    if (entry.timing) entry.timing.responseMs = performance.now();
    if (message.error && Object.hasOwn(message, 'result')) { entry.reject(new Error('Malformed LSP response')); }
    else if (message.error) {
      const error = new Error(`LSP request failed (${message.error.code})`);
      error.code = message.error.code;
      entry.reject(error);
    } else if (Object.hasOwn(message, 'result')) entry.resolve(message.result);
    else entry.reject(new Error('Malformed LSP response'));
  }
  close(error = new Error('LSP peer closed')) {
    if (this.failure) return;
    this.failure = error;
    for (const entry of this.pending.values()) { clearTimeout(entry.timer); entry.reject(error); }
    this.pending.clear();
  }
}
