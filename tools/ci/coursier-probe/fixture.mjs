// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { once } from 'node:events';
import { mkdir, readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { join } from 'node:path';
import { fileHash, hash, invoke, json, policy } from './io.mjs';

const basePath = '/fixture/probe/1/probe-1';
const jarPath = basePath + '.jar';
const pom = Buffer.from('<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>probe</artifactId><version>1</version></project>');
// The 22-byte ZIP end-of-central-directory is a valid empty JAR.
const jar = Buffer.from('504b0506' + '00'.repeat(18), 'hex');
const diagnostic = {
  missing: /checksum (?:errors|not found)/i,
  malformed: /checksum format error/i,
  mismatch: /wrong checksum/i,
};

function serve(path, kind) {
  const match = /^(.*\.(?:jar|pom))(?:\.(sha1|md5))?$/.exec(path);
  if (!match || ![jarPath, basePath + '.pom'].includes(match[1])) return null;
  const artifact = match[1] === jarPath ? jar : pom;
  const algorithm = match[2];
  if (!algorithm) return artifact;
  if (artifact === jar && kind !== 'valid') {
    if (kind === 'missing' || kind === 'permissive-missing') return null;
    if (kind === 'malformed') return Buffer.from('invalid!\n');
    if (kind === 'mismatch') return Buffer.from('0'.repeat(algorithm === 'sha1' ? 40 : 32));
  }
  return Buffer.from(createHash(algorithm).update(artifact).digest('hex'));
}

export function fixtureVerdict(kind, result, requests, bytesMatch) {
  if (result.signal || result.code == null) return 'inconclusive';
  const got = (path, status) => requests.some(r => r.path === path && r.method === 'GET' && r.status === status);
  if (!got(jarPath, 200)) return 'inconclusive';
  const missing = kind === 'missing' || kind === 'permissive-missing';
  if (!got(jarPath + '.sha1', missing ? 404 : 200)) return 'inconclusive';
  if (missing && !got(jarPath + '.md5', 404)) return 'inconclusive';
  const expectSuccess = kind === 'valid' || kind === 'permissive-missing';
  if (result.code === 0) return bytesMatch ? (expectSuccess ? 'pass' : 'fail') : 'inconclusive';
  if (expectSuccess) {
    const checksumFailure = /(?:wrong checksum|checksum (?:errors|not found|format error))/i.test(result.stderr);
    return checksumFailure && result.stderr.includes('probe-1.jar') ? 'fail' : 'inconclusive';
  }
  return diagnostic[kind].test(result.stderr) && result.stderr.includes('probe-1.jar') ? 'pass' : 'inconclusive';
}

export async function fixtures(coursier, out, temp) {
  const rows = [];
  for (const kind of ['valid', 'missing', 'malformed', 'mismatch', 'permissive-missing']) {
    const requests = [];
    const server = createServer((req, res) => {
      const body = serve(req.url, kind);
      const status = body === null ? 404 : 200;
      requests.push({ method: req.method, path: req.url, status, sha256: body && hash(body) });
      res.writeHead(status, { 'Content-Length': body?.length ?? 0, 'Connection': 'close' });
      res.end(req.method === 'HEAD' ? undefined : body);
    });
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    const dir = join(out, kind);
    try {
      const cache = join(temp, 'fixture-' + kind);
      await mkdir(cache);
      const checksum = kind === 'permissive-missing' ? policy.permissive : policy.strict;
      const args = ['-Xmx512m', '-jar', coursier, 'fetch', '--no-default', '--repository',
        'http://127.0.0.1:' + server.address().port, '--cache', cache, '--checksum', checksum, 'fixture:probe:1'];
      const result = await invoke(dir, 'java', args, 60_000, { ...process.env, COURSIER_CACHE: cache });
      let bytesMatch = false;
      if (result.code === 0) {
        const paths = (await readFile(join(dir, 'stdout.txt'), 'utf8')).trim().split(/\r?\n/);
        if (paths.length === 1 && paths[0].startsWith(cache + '/') && paths[0].endsWith('/probe-1.jar')) {
          bytesMatch = await fileHash(paths[0]) === hash(jar);
        }
      }
      const stderr = await readFile(join(dir, 'stderr.txt'), 'utf8');
      rows.push({ kind, checksum, ...result, bytesMatch, verdict: fixtureVerdict(kind, { ...result, stderr }, requests, bytesMatch) });
    } finally {
      await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
      await mkdir(dir, { recursive: true });
      await json(join(dir, 'requests.json'), requests);
    }
  }
  await json(join(out, 'results.json'), rows);
  assert(rows.every(row => row.verdict === 'pass'), 'unsatisfied controlled checksum cases');
}
