// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
// Independent fixture guard: every owned Node registers before executing fixture/helper code.
const { execFileSync } = require('node:child_process');
const { appendFileSync } = require('node:fs');
const { randomBytes } = require('node:crypto');
const token = 'owned-node-probe-' + randomBytes(16).toString('hex');
process.title = token;
const start = execFileSync('/bin/ps', ['-p', String(process.pid), '-o', 'lstart='], { encoding: 'utf8', timeout: 1000, maxBuffer: 4096, env: { ...process.env, LC_ALL: 'C', TZ: 'UTC' } }).trim();
appendFileSync(process.env.OWNED_NODE_REGISTRY, JSON.stringify({ pid: process.pid, uid: process.getuid(), start, token }) + '\n', { mode: 0o600 });
