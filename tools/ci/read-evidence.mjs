import { closeSync, constants, fstatSync, openSync, readSync } from 'node:fs';
import { isUtf8 } from 'node:buffer';
import { readWindowsEvidenceBytes } from './windows-evidence.mjs';

/** Open once, inspect that descriptor and read at most the stated byte budget. */
export function readEvidenceBytes(path, maxBytes) {
  if (!Number.isSafeInteger(maxBytes) || maxBytes < 1 || maxBytes > 512 * 1024 * 1024) throw new Error('Invalid evidence byte limit');
  if (process.platform === 'win32') return readWindowsEvidenceBytes(path, maxBytes);
  if (!Number.isInteger(constants.O_NOFOLLOW) || constants.O_NOFOLLOW === 0) throw new Error('No-follow evidence reads are unavailable on this platform');
  // Nonblocking open also prevents an unexpected FIFO from hanging the reader.
  const descriptor = openSync(path, constants.O_RDONLY | constants.O_NONBLOCK | constants.O_NOFOLLOW);
  try {
    const info = fstatSync(descriptor);
    if (!info.isFile()) throw new Error('Evidence must be a regular file');
    if (info.size > maxBytes) throw new Error(`Evidence exceeds ${maxBytes} byte limit`);
    const chunks = [];
    let total = 0;
    while (true) {
      // Include one sentinel byte to detect growth after fstat without an
      // unbounded allocation or a second path lookup that could name a new file.
      const chunk = Buffer.alloc(Math.min(64 * 1024, maxBytes - total + 1));
      const count = readSync(descriptor, chunk, 0, chunk.length, null);
      if (count === 0) break;
      total += count;
      if (total > maxBytes) throw new Error(`Evidence exceeds ${maxBytes} byte limit`);
      chunks.push(chunk.subarray(0, count));
    }
    return Buffer.concat(chunks, total);
  } finally {
    closeSync(descriptor);
  }
}

export function readEvidenceText(path, maxBytes) {
  const bytes = readEvidenceBytes(path, maxBytes);
  if (!isUtf8(bytes)) throw new Error('Evidence is not valid UTF-8');
  return bytes.toString('utf8');
}
