import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { runCommand } from './command.mjs';

const supported = ['darwin', 'linux'].includes(process.platform);
function descendantFixture(t, exitAfterReady) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'gvy-native-command-'));
  const pidFile = path.join(directory, 'ready.pid');
  t.after(() => {
    if (fs.existsSync(pidFile)) {
      const pid = Number(fs.readFileSync(pidFile, 'utf8'));
      if (Number.isSafeInteger(pid) && pid > 0) {
        try { process.kill(pid, 'SIGKILL'); } catch (error) { if (error.code !== 'ESRCH') throw error; }
      }
    }
    fs.rmSync(directory, { recursive: true, force: true });
  });
  const descendant = 'process.send("ready");setInterval(()=>{},1000)';
  const leader = `const {spawn}=require('node:child_process');const fs=require('node:fs');const child=spawn(process.execPath,['-e',${JSON.stringify(descendant)}],{stdio:['ignore','ignore','ignore','ipc']});child.once('message',()=>{fs.writeFileSync(process.argv[1],String(child.pid));${exitAfterReady ? 'process.exit(0)' : 'setInterval(()=>{},1000)'}});`;
  return { pidFile, args: ['-e', leader, pidFile] };
}

test('owned command returns complete stdout and drains stderr', { skip: !supported }, async () => {
  const result = await runCommand(process.execPath, ['-e', 'process.stdin.pipe(process.stdout);process.stderr.write("diagnostic")'], { input: 'complete' });
  assert.equal(result, 'complete');
});

test('an unavailable command fails without a live process group', { skip: !supported }, async () => {
  await assert.rejects(runCommand(`gvy-native-missing-command-${process.pid}`, []), /NATIVE_COMMAND_FAILED/);
});

test('native failures expose only a known fixed diagnostic and never raw stderr', { skip: !supported }, async () => {
  await assert.rejects(runCommand(process.execPath, ['-e', 'process.stderr.write("NATIVE_CONTRACT_FAILED:PREDICATE_ORACLE_FAILED\\n");process.exitCode=1'], { nativeProbe: true }), /NATIVE_PROBE_PREDICATE_ORACLE_FAILED/);
  const canary = 'PRIVATE_CANARY_7821';
  await assert.rejects(runCommand(process.execPath, ['-e', `process.stdout.write(${JSON.stringify(canary)});process.stderr.write(${JSON.stringify(`${canary}\nNATIVE_CONTRACT_FAILED:INPUT_INVALID\n`)});process.exitCode=1`], { nativeProbe: true }), error => error.message === 'NATIVE_PROBE_FAILED' && !error.message.includes(canary) && error.nativeOutput === undefined);
  await assert.rejects(runCommand(process.execPath, ['-e', 'process.stderr.write("NATIVE_CONTRACT_FAILED:INPUT_INVALID\\n");process.exitCode=1']), /NATIVE_COMMAND_FAILED/);
});

test('known native failure retains bounded stdout privately for embedded fixture verification', { skip: !supported }, async () => {
  await assert.rejects(runCommand(process.execPath, ['-e', 'process.stdout.write("fixture-envelope");process.stderr.write("NATIVE_CONTRACT_FAILED:IDENTIFIER_BOUNDARY_FAILED_SECONDARY_VARIABLE\\n");process.exitCode=1'], { nativeProbe: true }), error => error.message === 'NATIVE_PROBE_IDENTIFIER_BOUNDARY_FAILED_SECONDARY_VARIABLE' && error.nativeOutput === 'fixture-envelope' && error.nativeExitCode === 1);
});

test('output overflow and timeout reject rather than return partial success', { skip: !supported }, async () => {
  await assert.rejects(runCommand(process.execPath, ['-e', 'process.stdout.write("x".repeat(4096))'], { outputLimit: 128 }), /NATIVE_COMMAND_OUTPUT_LIMIT/);
  await assert.rejects(runCommand(process.execPath, ['-e', 'setInterval(()=>{},1000)'], { timeoutMs: 100 }), /NATIVE_COMMAND_TIMEOUT/);
});

test('leader exit cannot leave a ready descendant running', { skip: !supported, timeout: 10000 }, async t => {
  const { pidFile, args } = descendantFixture(t, true);
  await assert.rejects(runCommand(process.execPath, args, { timeoutMs: 5000 }), /NATIVE_COMMAND_DESCENDANTS/);
  const pid = Number(fs.readFileSync(pidFile, 'utf8'));
  assert.ok(Number.isSafeInteger(pid) && pid > 0);
  assert.throws(() => process.kill(pid, 0), { code: 'ESRCH' });
});

test('timeout terminates a definitely started live descendant', { skip: !supported, timeout: 12000 }, async t => {
  const { pidFile, args } = descendantFixture(t, false);
  const terminal = runCommand(process.execPath, args, { timeoutMs: 5000 }).then(
    () => ({ code: 'unexpected-success' }), error => ({ code: error.message }),
  );
  const readyDeadline = performance.now() + 4000;
  while (!fs.existsSync(pidFile) && performance.now() < readyDeadline) await new Promise(resolve => setTimeout(resolve, 10));
  const pid = fs.existsSync(pidFile) ? Number(fs.readFileSync(pidFile, 'utf8')) : undefined;
  let observedLive = false;
  if (pid !== undefined) { try { process.kill(pid, 0); observedLive = true; } catch {} }
  const outcome = await terminal;
  assert.ok(observedLive, 'descendant must be ready and alive before the timeout');
  assert.equal(outcome.code, 'NATIVE_COMMAND_TIMEOUT');
  assert.throws(() => process.kill(pid, 0), { code: 'ESRCH' });
});

test('an already cancelled command never starts', { skip: !supported }, async () => {
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(runCommand(process.execPath, ['-e', 'process.exit(0)'], { signal: controller.signal }), /NATIVE_COMMAND_ABORTED/);
});

test('cancelling an in-flight command terminates its ready descendant', { skip: !supported, timeout: 10000 }, async t => {
  const { pidFile, args } = descendantFixture(t, false);
  const controller = new AbortController();
  const terminal = runCommand(process.execPath, args, { timeoutMs: 5000, signal: controller.signal }).then(
    () => ({ code: 'unexpected-success' }), error => ({ code: error.message }),
  );
  const readyDeadline = performance.now() + 4000;
  while (!fs.existsSync(pidFile) && performance.now() < readyDeadline) await new Promise(resolve => setTimeout(resolve, 10));
  const pid = fs.existsSync(pidFile) ? Number(fs.readFileSync(pidFile, 'utf8')) : undefined;
  let observedLive = false;
  if (pid !== undefined) { try { process.kill(pid, 0); observedLive = true; } catch {} }
  controller.abort();
  const outcome = await terminal;
  assert.ok(observedLive, 'descendant must be ready and alive before cancellation');
  assert.equal(outcome.code, 'NATIVE_COMMAND_ABORTED');
  assert.throws(() => process.kill(pid, 0), { code: 'ESRCH' });
});
