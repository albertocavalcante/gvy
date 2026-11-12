// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import {
  appendFileSync,
  lstatSync,
  mkdtempSync,
  mkdirSync,
  readFileSync,
  renameSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, before, test } from 'node:test';
import { readWindowsEvidenceBytes } from './windows-evidence.mjs';

const priorReader = process.env.GVY_CI_EVIDENCE_READER;

test('Windows evidence rejects invalid budgets without starting a process', () => {
  for (const limit of [0, -1, NaN, Infinity, 0.5, 1024 ** 3]) {
    assert.throws(
      () => readWindowsEvidenceBytes('PRIVATE-PATH', limit),
      /byte limit/,
    );
  }
});

test('missing, relative and non-file reader setup fails closed without a path leak', () => {
  const previous = process.env.GVY_CI_EVIDENCE_READER;
  const dir = mkdtempSync(join(tmpdir(), 'gvy-reader-setup-'));
  try {
    for (const value of [
      undefined,
      '',
      'relative.exe',
      join(dir, 'PRIVATE-MISSING.exe'),
      dir,
    ]) {
      if (value === undefined) delete process.env.GVY_CI_EVIDENCE_READER;
      else process.env.GVY_CI_EVIDENCE_READER = value;
      assert.throws(
        () => readWindowsEvidenceBytes('PRIVATE-PATH', 1024),
        (error) =>
          /compiled no-follow/.test(error.message) &&
          !error.message.includes('PRIVATE'),
      );
    }
  } finally {
    if (previous === undefined) delete process.env.GVY_CI_EVIDENCE_READER;
    else process.env.GVY_CI_EVIDENCE_READER = previous;
    rmSync(dir, { recursive: true, force: true });
  }
});

if (process.platform === 'win32') {
  const root = mkdtempSync(join(tmpdir(), 'gvy-reader-native-'));
  const source = readFileSync(
    new URL('./windows-evidence-reader.c', import.meta.url),
    'utf8',
  );
  let safe;
  let unsafe;
  let beforeOpen;
  let afterOpen;
  let privateError;
  let waiting;
  const sourceOpen = 'HANDLE file =';
  const sourceInspected = 'BY_HANDLE_FILE_INFORMATION information;';
  function compile(name, text) {
    const directory = join(root, name);
    mkdirSync(directory);
    const file = join(directory, 'reader.c');
    const executable = join(directory, 'windows-evidence-reader.exe');
    writeFileSync(file, text);
    const result = spawnSync(
      process.env.GVY_PROBE_CC || 'gcc',
      [
        '-std=c11',
        '-O2',
        '-static',
        '-Wall',
        '-Wextra',
        '-Werror',
        file,
        '-o',
        executable,
      ],
      { timeout: 30000, encoding: 'utf8' },
    );
    assert.ifError(result.error);
    assert.equal(result.status, 0, result.stderr);
    return executable;
  }
  function barrier(text, needle) {
    assert.equal(text.split(needle).length, 2);
    return text.replace(
      needle,
      `{
      wchar_t ack[32768];
      DWORD length = GetEnvironmentVariableW(L"GVY_READER_TEST_ACK", ack, 32768);
      if (length == 0 || length >= 32768) return fail(20, "Missing test barrier");
      DWORD written;
      WriteFile(GetStdHandle(STD_ERROR_HANDLE), "opened\\n", 7, &written, NULL);
      ULONGLONG deadline = GetTickCount64() + 10000;
      while (GetFileAttributesW(ack) == INVALID_FILE_ATTRIBUTES) {
        if (GetTickCount64() > deadline) return fail(21, "Test barrier timeout");
        Sleep(5);
      }
    }
    ${needle}`,
    );
  }
  before(() => {
    safe = compile('safe', source);
    assert.equal(source.split('FILE_FLAG_OPEN_REPARSE_POINT |').length, 2);
    unsafe = compile(
      'unsafe-control',
      source.replace('FILE_FLAG_OPEN_REPARSE_POINT |', ''),
    );
    beforeOpen = compile('before-open', barrier(source, sourceOpen));
    afterOpen = compile('after-open', barrier(source, sourceInspected));
    privateError = compile(
      'private-error',
      '#include <stdio.h>\nint main(void) { fputs("PRIVATE-CANARY-PATH", stderr); return 42; }\n',
    );
    waiting = compile(
      'timeout',
      '#include <windows.h>\nint main(void) { Sleep(15000); return 0; }\n',
    );
    process.env.GVY_CI_EVIDENCE_READER = safe;
  });
  after(() => {
    if (priorReader === undefined) delete process.env.GVY_CI_EVIDENCE_READER;
    else process.env.GVY_CI_EVIDENCE_READER = priorReader;
    rmSync(root, {
      recursive: true,
      force: true,
      maxRetries: 20,
      retryDelay: 50,
    });
  });
  function withReader(executable, action) {
    process.env.GVY_CI_EVIDENCE_READER = executable;
    try {
      return action();
    } finally {
      process.env.GVY_CI_EVIDENCE_READER = safe;
    }
  }
  async function paused(executable, path, action) {
    const ack = join(root, `ack-${Date.now()}-${Math.random()}`);
    const child = spawn(executable, ['4096'], {
      env: { ...process.env, GVY_READER_TEST_ACK: ack },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    const stdout = [];
    child.stdout.on('data', (chunk) => stdout.push(chunk));
    const closed = new Promise((resolve, reject) => {
      child.once('error', reject);
      child.once('close', (code) => resolve(code));
    });
    const ready = new Promise((resolve, reject) => {
      let text = '';
      const timer = setTimeout(
        () => reject(new Error('Reader did not reach its controlled barrier')),
        5000,
      );
      child.stderr.on('data', (chunk) => {
        text += chunk.toString();
        if (text.includes('opened\n')) {
          clearTimeout(timer);
          resolve();
        }
      });
      child.once('error', (error) => {
        clearTimeout(timer);
        reject(error);
      });
      child.once('close', () => {
        clearTimeout(timer);
        reject(new Error('Reader closed before its barrier'));
      });
    });
    child.stdin.end(Buffer.from(path, 'utf8'));
    try {
      await ready;
      action();
      writeFileSync(ack, 'continue');
      return { status: await closed, stdout: Buffer.concat(stdout) };
    } finally {
      if (child.exitCode === null && child.signalCode === null)
        child.kill('SIGKILL');
      await closed;
      rmSync(ack, { force: true });
    }
  }

  test('the opened Windows handle preserves Unicode paths and exact binary bytes at the limit', () => {
    const file = join(root, 'evidence 世界 λ 😀.bin');
    const bytes = Buffer.from([0xff, 0, 13, 10, 0xef, 0xbf, 0xbd]);
    writeFileSync(file, bytes);
    assert.deepEqual(readWindowsEvidenceBytes(file, bytes.length), bytes);
    assert.throws(
      () => readWindowsEvidenceBytes(file, bytes.length - 1),
      /exceeds/,
    );
    const multipleChunks = Buffer.alloc(128 * 1024 + 17);
    for (let index = 0; index < multipleChunks.length; index++)
      multipleChunks[index] = index % 251;
    writeFileSync(file, multipleChunks);
    assert.deepEqual(
      readWindowsEvidenceBytes(file, multipleChunks.length),
      multipleChunks,
    );
    writeFileSync(file, '');
    assert.equal(readWindowsEvidenceBytes(file, 1).length, 0);
  });

  test('a real unsafe-open mutant reads the private target that the production flag refuses', () => {
    const target = join(root, 'target');
    const link = join(root, 'link');
    writeFileSync(target, 'PRIVATE-CANARY');
    symlinkSync(target, link, 'file');
    assert.equal(
      withReader(unsafe, () => readWindowsEvidenceBytes(link, 1024)).toString(),
      'PRIVATE-CANARY',
    );
    assert.throws(
      () => readWindowsEvidenceBytes(link, 1024),
      /regular file without links/,
    );
  });

  test('a replacement before atomic open is checked as an opened link or junction', async () => {
    for (const type of ['file', 'junction']) {
      const file = join(root, `before-${type}`);
      const original = file + '-original';
      const target = file + '-target';
      writeFileSync(file, 'PUBLIC');
      if (type === 'file') writeFileSync(target, 'PRIVATE-CANARY');
      else mkdirSync(target);
      const result = await paused(beforeOpen, file, () => {
        renameSync(file, original);
        symlinkSync(target, file, type);
      });
      assert.equal(result.status, 3);
      assert.equal(result.stdout.length, 0);
      assert.equal(lstatSync(file).isSymbolicLink(), true);
    }
  });

  test('the opened handle prevents pathname replacement and growth until the original bytes are captured', async () => {
    const file = join(root, 'opened-object');
    const moved = file + '-moved';
    writeFileSync(file, 'ORIGINAL');
    const denied = (error) => ['EPERM', 'EACCES', 'EBUSY'].includes(error.code);
    const result = await paused(afterOpen, file, () => {
      assert.throws(() => renameSync(file, moved), denied);
      assert.throws(() => appendFileSync(file, 'GROWTH'), denied);
    });
    assert.equal(result.status, 0);
    assert.equal(result.stdout.toString(), 'ORIGINAL');
    renameSync(file, moved);
    appendFileSync(moved, 'GROWTH');
    assert.equal(readFileSync(moved, 'utf8'), 'ORIGINALGROWTH');
  });

  test('missing, nonregular and oversized objects reject without exposing private paths', () => {
    assert.throws(
      () => readWindowsEvidenceBytes(join(root, 'PRIVATE-MISSING'), 1024),
      (error) =>
        /ENOENT/.test(error.message) &&
        !error.message.includes('PRIVATE-MISSING'),
    );
    assert.throws(() => readWindowsEvidenceBytes(root, 1024), /regular file/);
    assert.throws(
      () => readWindowsEvidenceBytes('\\\\.\\NUL', 1024),
      /regular file|safely read/,
    );
    const large = join(root, 'large');
    writeFileSync(large, Buffer.alloc(128 * 1024, 0xff));
    assert.throws(() => readWindowsEvidenceBytes(large, 64 * 1024), /exceeds/);
    assert.throws(
      () => readWindowsEvidenceBytes('bad\ud800', 1024),
      /Invalid Windows evidence path/,
    );
  });

  test(
    'helper failures and timeouts return no stderr canary or partial evidence',
    { timeout: 15000 },
    () => {
      for (const executable of [privateError, waiting]) {
        withReader(executable, () =>
          assert.throws(
            () => readWindowsEvidenceBytes('PRIVATE-PATH', 1024),
            (error) =>
              /Cannot safely read/.test(error.message) &&
              !error.message.includes('PRIVATE') &&
              error.cause === undefined,
          ),
        );
      }
    },
  );
}
