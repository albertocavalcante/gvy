import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { runCommand } from './command.mjs';

const supported = ['darwin', 'linux'].includes(process.platform);
const rust = (script, options = {}) => runCommand(process.execPath, ['-e', script], { rustDiagnostics: true, ...options });
const canary = 'PRIVATE_CANARY_7821';
const failure = (code, capture) => error => {
  assert.equal(error.message, code);
  assert.deepEqual(error.rustCapture, capture);
  assert.deepEqual(Object.keys(error).sort(), ['rustCapture']);
  assert.ok(!JSON.stringify(error).includes(canary));
  return true;
};

test('Rust opt-in accepts complete clean nonempty channels without changing stdout', { skip: !supported }, async () => {
  assert.equal(await rust('process.stdout.write("{}\\n");process.stderr.write("info: ready\\n")'), '{}\n');
  assert.equal(await rust('process.stderr.write("info: cached\\n")'), '');
  assert.equal(await rust('process.stdout.write("info: ready\\n")'), 'info: ready\n');
  assert.equal(await rust('process.stdout.write(Buffer.from([105,110,102,111,58,32,240,159]));setTimeout(()=>process.stdout.write(Buffer.from([152,128,10])),20)'), 'info: 😀\n');
});

test('zero-exit warnings and errors on either original channel reject without exporting text', { skip: !supported }, async () => {
  for (const channel of ['stdout', 'stderr']) for (const prefix of ['warning:', 'error[E0308]:']) {
    const script = `process.${channel}.write(${JSON.stringify(`${prefix} ${canary}\n`)});`;
    await assert.rejects(rust(script), failure('NATIVE_RUST_DIAGNOSTICS', { complete: true, stdout: channel === 'stdout' ? 'diagnostics' : 'empty', stderr: channel === 'stderr' ? 'diagnostics' : 'empty' }));
  }
});

test('invalid UTF8 controls and partial channel endings reject despite another clean channel', { skip: !supported }, async () => {
  for (const channel of ['stdout', 'stderr']) for (const bytes of [
    [0xff, 0x0a], [...Buffer.from(`${canary}\0\n`)], [...Buffer.from('info: partial')],
    [...Buffer.from('info: bare\rline\n')], [...Buffer.from('\u001b[2Kinfo: hidden\n')],
  ]) {
    const other = channel === 'stdout' ? 'stderr' : 'stdout';
    await assert.rejects(rust(`process.${other}.write("info: clean\\n");process.${channel}.write(Buffer.from(${JSON.stringify(bytes)}));`), failure('NATIVE_RUST_CAPTURE_INVALID', { complete: true, stdout: channel === 'stdout' ? 'invalid' : 'clean', stderr: channel === 'stderr' ? 'invalid' : 'clean' }));
  }
  await assert.rejects(rust(''), failure('NATIVE_RUST_CAPTURE_EMPTY', { complete: true, stdout: 'empty', stderr: 'empty' }));
});

test('nonzero command failure retains priority and safe complete capture classification', { skip: !supported }, async () => {
  await assert.rejects(rust(`process.stdout.write("info: failed\\n");process.stderr.write(${JSON.stringify(`warning: ${canary}\n`)});process.exitCode=2`), failure('NATIVE_COMMAND_FAILED', { complete: true, stdout: 'clean', stderr: 'diagnostics' }));
  await assert.rejects(rust('process.stdout.write(Buffer.from([255]));process.exitCode=2'), failure('NATIVE_COMMAND_FAILED', { complete: true, stdout: 'invalid', stderr: 'empty' }));
});

test('Rust output limit timeout and abort never certify partial channels as complete', { skip: !supported }, async () => {
  const partial = code => error => {
    assert.equal(error.message, code);
    assert.equal(error.rustCapture.complete, false);
    for (const channel of ['stdout', 'stderr']) assert.ok(['empty', 'clean', 'diagnostics', 'invalid'].includes(error.rustCapture[channel]));
    assert.ok(!JSON.stringify(error).includes(canary));
    return true;
  };
  const stdout = `${'i'.repeat(79)}\n`;
  const stderr = `warning: ${canary}${'x'.repeat(40)}\n`;
  assert.ok(Buffer.byteLength(stdout) < 128 && Buffer.byteLength(stderr) < 128 && Buffer.byteLength(stdout + stderr) > 128);
  await assert.rejects(rust(`process.stdout.write(${JSON.stringify(stdout)});process.stderr.write(${JSON.stringify(stderr)});`, { outputLimit: 128 }), partial('NATIVE_COMMAND_OUTPUT_LIMIT'));
  await assert.rejects(rust(`process.stderr.write(${JSON.stringify(`warning: ${canary}\n`)});setInterval(()=>{},1000)`, { timeoutMs: 500 }), partial('NATIVE_COMMAND_TIMEOUT'));
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(rust('process.exit(0)', { signal: controller.signal }), failure('NATIVE_COMMAND_ABORTED', { complete: false, stdout: 'empty', stderr: 'empty' }));
});

test('Rust and native-probe diagnostic modes cannot be combined or silently mistyped', { skip: !supported }, async () => {
  await assert.rejects(rust('process.exit(0)', { nativeProbe: true }), /NATIVE_COMMAND_OPTIONS_INVALID/);
  await assert.rejects(runCommand(process.execPath, ['-e', 'process.exit(0)'], { rustDiagnostics: 'true' }), /NATIVE_COMMAND_OPTIONS_INVALID/);
});

test('driver opts Cargo metadata into the canonical gate without Cargo or compilation', { skip: !supported }, t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'gvy-fake-cargo-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const marker = path.join(directory, 'args.json');
  const fake = `#!${process.execPath}\nrequire('node:fs').writeFileSync(${JSON.stringify(marker)},JSON.stringify(process.argv.slice(2)));process.stdout.write("{}\\n");process.stderr.write(${JSON.stringify(`warning: ${canary}\n`)});\n`;
  fs.writeFileSync(path.join(directory, 'cargo'), fake, { mode: 0o700 });
  const cli = fileURLToPath(new URL('./run.mjs', import.meta.url));
  const result = spawnSync(process.execPath, [cli, 'primary', '--verify-only'], { env: { ...process.env, PATH: `${directory}${path.delimiter}${process.env.PATH}` }, encoding: 'utf8', timeout: 5000, maxBuffer: 16384 });
  assert.equal(result.status, 1);
  assert.equal(JSON.parse(fs.readFileSync(marker, 'utf8'))[0], 'metadata');
  assert.equal(result.stdout, '');
  assert.equal(result.stderr, 'NATIVE_RUST_DIAGNOSTICS\n');
});

test('cleanup uncertainty overrides a Rust diagnostic and never certifies complete capture', { skip: !supported }, () => {
  const helper = new URL('./command.mjs', import.meta.url).href;
  const script = `const {runCommand}=await import(${JSON.stringify(helper)});const kill=process.kill.bind(process);process.kill=(pid,signal)=>{if(pid<0&&signal===0)throw Object.assign(new Error('probe uncertainty'),{code:'EPERM'});return kill(pid,signal);};try{await runCommand(process.execPath,['-e',${JSON.stringify(`process.stderr.write('warning: ${canary}\n')`)}],{rustDiagnostics:true,timeoutMs:4000});}catch(error){process.stdout.write(JSON.stringify({code:error.message,capture:error.rustCapture}));}`;
  const result = spawnSync(process.execPath, ['--input-type=module', '-e', script], { timeout: 7000, maxBuffer: 4096, encoding: 'utf8' });
  assert.equal(result.status, 0);
  assert.equal(result.stderr, '');
  const observed = JSON.parse(result.stdout);
  assert.equal(observed.code, 'NATIVE_COMMAND_CLEANUP_UNKNOWN');
  assert.equal(observed.capture.complete, false);
  assert.ok(!result.stdout.includes(canary));
});
