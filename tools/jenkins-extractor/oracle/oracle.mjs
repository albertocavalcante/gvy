// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { createHash } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { spawn, execFileSync } from 'node:child_process';
import { readFile, writeFile, mkdir, mkdtemp, rm, copyFile, open, statfs } from 'node:fs/promises';
import { resolve, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

export const directory = dirname(fileURLToPath(import.meta.url));
export const digest = bytes => createHash('sha256').update(bytes).digest('hex');
export const json = async file => JSON.parse(await readFile(file, 'utf8'));


export async function captureInput(file) {
  const bytes = await readFile(file);
  return { bytes, sha256: digest(bytes) };
}

export async function stageInput(file, captured) {
  await writeFile(file, captured.bytes, { mode: 0o600 });
  await verifyArtifact(file, captured.sha256);
}

export function parseLock(bytes, profile) {
  const lock = JSON.parse(bytes.toString('utf8'));
  if (lock.profile !== profile) throw Error('lock does not match requested profile: ' + profile);
  validateLock(lock);
  return lock;
}

export function manifest(text) {
  const fields = Object.fromEntries(text.replace(/\r\n/g, '\n').replace(/\n /g, '').split('\n\n')[0]
    .split('\n').filter(Boolean).map(line => {
      const colon = line.indexOf(': ');
      if (colon < 1) throw Error('invalid manifest field');
      return [line.slice(0, colon), line.slice(colon + 2)];
    }));
  const dependencies = (fields['Plugin-Dependencies'] || '').split(',').filter(Boolean).map(entry => {
    const [identity, ...options] = entry.split(';');
    const [id, minimumVersion] = identity.split(':');
    if (!id || !minimumVersion || options.some(option => option !== 'resolution:=optional')) throw Error('invalid dependency');
    return { id, minimumVersion, optional: options.length === 1 };
  });
  return { id: fields['Short-Name'], version: fields['Plugin-Version'],
    requiredCore: fields['Jenkins-Version'] || fields['Hudson-Version'], dependencies };
}

export function archiveManifest(file) {
  return manifest(execFileSync('unzip', ['-p', file, 'META-INF/MANIFEST.MF'], { maxBuffer: 1024 * 1024 }).toString());
}

export async function verifyArtifact(file, sha256) {
  let bytes;
  try { bytes = await readFile(file); } catch { throw Error('oracle input unavailable: ' + file); }
  if (!/^[a-f0-9]{64}$/.test(sha256) || digest(bytes) !== sha256) throw Error('oracle input SHA-256 mismatch: ' + file);
  return bytes;
}

export function verifyGraph(plugins) {
  const ids = new Set(plugins.map(plugin => plugin.id));
  if (ids.size !== plugins.length) throw Error('duplicate plugin identity');
  for (const plugin of plugins) {
    for (const dependency of plugin.dependencies) {
      if (!dependency.optional && !ids.has(dependency.id)) throw Error('missing mandatory dependency: ' + dependency.id);
    }
  }
}

export async function withHome(parent, action) {
  await mkdir(parent, { recursive: true, mode: 0o700 });
  const controller = new AbortController();
  const interrupted = () => controller.abort(Error('oracle interrupted'));
  process.once('SIGINT', interrupted);
  process.once('SIGTERM', interrupted);
  let home;
  let result;
  let retainHome = false;
  try {
    home = await mkdtemp(join(parent, 'gvy-oracle-'));
    controller.signal.throwIfAborted();
    result = await action(home, controller.signal);
    controller.signal.throwIfAborted();
  } catch (error) {
    retainHome = error.retainHome === true;
    if (retainHome) error.message += "; retained owned home: " + home;
    throw error;
  } finally {
    try { if (home && !retainHome) await rm(home, { recursive: true, force: true }); }
    finally {
      process.removeListener('SIGINT', interrupted);
      process.removeListener('SIGTERM', interrupted);
    }
  }
  controller.signal.throwIfAborted();
  return result;
}

// Own a process group and settle only after the complete group has drained.
export async function runProcess(command, args, { timeout, env = process.env, log, signal, guard, cwd } = {}) {
  signal?.throwIfAborted();
  await guard?.();
  signal?.throwIfAborted();
  const fd = log ? await open(log, 'wx', 0o600) : undefined;
  try {
    await new Promise((resolvePromise, reject) => {
      const child = spawn(command, args, { env, cwd, detached: true, stdio: ['ignore', fd?.fd ?? 'ignore', fd?.fd ?? 'ignore'] });
      let failure;
      let receipt;
      let finishing = false;
      let settled = false;
      let termAt;
      let killAt;
      let drained = !child.pid;

      const groupExists = () => {
        if (drained || !child.pid) return false;
        try { process.kill(-child.pid, 0); return true; }
        catch (error) {
          if (error.code !== 'ESRCH') throw error;
          drained = true;
          return false;
        }
      };
      const send = signalName => {
        if (!groupExists()) return;
        try { process.kill(-child.pid, signalName); }
        catch (error) { if (error.code !== 'ESRCH') throw error; drained = true; }
      };
      const terminate = reason => {
        if (settled) return;
        failure ||= Error(reason);
        if (termAt === undefined) {
          termAt = Date.now();
          try { send('SIGTERM'); } catch (error) { failure = error; }
        }
      };
      const onSignal = () => terminate('oracle interrupted');
      signal?.addEventListener('abort', onSignal, { once: true });
      if (signal?.aborted) onSignal();
      process.once('SIGINT', onSignal);
      process.once('SIGTERM', onSignal);
      const timer = setTimeout(() => terminate('oracle process timed out'), timeout ?? 180000);
      const monitor = setInterval(() => {
        if (termAt !== undefined && killAt === undefined && Date.now() - termAt >= 3000) {
          killAt = Date.now();
          try { send('SIGKILL'); } catch (error) { failure = error; }
        }
      }, 50);
      const guardTimer = guard ? setInterval(() => {
        Promise.resolve().then(guard).catch(error => terminate(error.message));
      }, 1000) : undefined;
      child.once('spawn', () => {
        receipt = { pid: child.pid, pgid: child.pid, startedAt: new Date().toISOString(), command, args, timeout, cwd: cwd ?? process.cwd() };
        if (log) {
          try { writeFileSync(log + '.process.json', JSON.stringify(receipt, null, 2) + '\n', { mode: 0o600 }); }
          catch (error) { terminate('cannot record owned process: ' + error.message); }
        }
      });
      const finish = async error => {
        if (finishing) return;
        finishing = true;
        failure ||= error;
        try {
          if (groupExists()) terminate('oracle leader exited with live descendants');
          while (groupExists()) {
            if (killAt !== undefined && Date.now() - killAt >= 3000) {
              throw Error('owned process group did not drain: ' + child.pid);
            }
            await new Promise(resolve => setTimeout(resolve, 50));
          }
        } catch (drainError) {
          drainError.retainHome = true;
          failure = drainError;
        }
        settled = true;
        clearTimeout(timer);
        clearInterval(monitor);
        clearInterval(guardTimer);
        signal?.removeEventListener('abort', onSignal);
        process.removeListener('SIGINT', onSignal);
        process.removeListener('SIGTERM', onSignal);
        if (log && receipt) {
          try {
            writeFileSync(log + '.process.json', JSON.stringify({
              ...receipt, endedAt: new Date().toISOString(), groupDrained: drained, ok: !failure,
            }, null, 2) + '\n', { mode: 0o600 });
          } catch (receiptError) { failure ||= receiptError; }
        }
        if (failure) reject(failure); else resolvePromise();
      };
      child.once('error', error => { void finish(Error('oracle executable unavailable: ' + error.message)); });
      child.once('close', (code, exitSignal) => {
        void finish(code === 0 ? undefined : Error('oracle process exit ' + code + ' signal ' + exitSignal));
      });
    });
  } finally { await fd?.close(); }
}

export async function diskGuard(...paths) {
  for (const path of paths) {
    const space = await statfs(path);
    if (space.bavail * space.bsize < 4 * 1024 ** 3) throw Error('oracle needs at least 4 GiB free: ' + path);
  }
}

export function artifactIdentity(artifact) {
  if (!artifact || typeof artifact !== 'object' || Array.isArray(artifact)) throw Error('invalid artifact metadata');
  const { id, version, file, url, sha256 } = artifact;
  let expectedFile;
  let expectedUrl;
  if (id !== undefined) {
    if (typeof id !== 'string' || typeof version !== 'string' || !/^[a-z0-9][a-z0-9-]*$/.test(id) || !/^[a-zA-Z0-9][a-zA-Z0-9._+-]{0,127}$/.test(version)) throw Error('invalid plugin identity');
    expectedFile = id + '-' + version + '.hpi';
    expectedUrl = 'https://updates.jenkins.io/download/plugins/' + id + '/' + version + '/' + id + '.hpi';
  } else {
    if (typeof version !== 'string' || !/^\d+(\.\d+)+$/.test(version)) throw Error('invalid core identity');
    expectedFile = 'jenkins-' + version + '.war';
    expectedUrl = 'https://get.jenkins.io/war-stable/' + version + '/jenkins.war';
  }
  if (file !== expectedFile) throw Error('invalid artifact filename');
  if (url !== expectedUrl) throw Error('invalid artifact URL');
  if (typeof sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(sha256)) throw Error('invalid artifact digest');
  return Object.freeze({ file: expectedFile, url: expectedUrl, sha256 });
}

export function validateLock(lock) {
  if (lock.schemaVersion !== 1 || !['current', 'legacy'].includes(lock.profile) || !Array.isArray(lock.plugins) || !lock.plugins.length) throw Error('unsupported oracle lock');
  const identity = /^[a-z0-9][a-z0-9-]*$/;
  const version = /^[a-zA-Z0-9][a-zA-Z0-9._+-]{0,127}$/;
  for (const plugin of lock.plugins) {
    if (!identity.test(plugin.id) || !version.test(plugin.version) || !/^\d+(\.\d+)+$/.test(plugin.requiredCore)) throw Error('invalid plugin identity');
    artifactIdentity(plugin);
    if (!Array.isArray(plugin.dependencies)) throw Error('invalid dependencies');
    for (const edge of plugin.dependencies) {
      if (!identity.test(edge.id) || !version.test(edge.minimumVersion) || typeof edge.optional !== 'boolean') throw Error('invalid dependency');
    }
  }
  if (!/^\d+(\.\d+)+$/.test(lock.core?.version) || lock.core.file !== 'jenkins-' + lock.core.version + '.war') throw Error('invalid core');
  artifactIdentity(lock.core);
  verifyGraph(lock.plugins);
}

export async function verifyInputs(lock, cache) {
  validateLock(lock);
  const target = (await json(join(directory, 'roots.json'))).profiles[lock.profile];
  if (target.core.version !== lock.core.version || target.core.sha256 !== lock.core.sha256 ||
      target.converterRevision !== lock.converterRevision) throw Error('unapproved compatibility target');
  for (const root of [...target.roots, ...target.overrides]) {
    const plugin = lock.plugins.find(candidate => candidate.id === root.id);
    if (plugin?.version !== root.version || plugin.sha256 !== root.sha256) throw Error('unapproved root: ' + root.id);
  }
  for (const artifact of [lock.core, ...lock.plugins]) {
    if (!/^[a-zA-Z0-9._-]+$/.test(artifact.file)) throw Error('invalid artifact filename');
    await verifyArtifact(join(cache, artifact.file), artifact.sha256);
  }
  if (archiveManifest(join(cache, lock.core.file)).requiredCore !== lock.core.version) throw Error('core manifest mismatch');
  for (const plugin of lock.plugins) {
    const actual = archiveManifest(join(cache, plugin.file));
    for (const field of ['id', 'version', 'requiredCore', 'dependencies']) {
      if (JSON.stringify(actual[field]) !== JSON.stringify(plugin[field])) throw Error('manifest mismatch: ' + plugin.id + ' ' + field);
    }
  }
}

export function assertOracleHost() {
  if (process.platform === 'win32') throw Error('oracle requires Linux or macOS process groups');
  if (process.getuid?.() === 0) throw Error('oracle refuses root');
}

export async function oracle(profile, cache, evidence, java) {
  assertOracleHost();
  const lockPath = join(directory, 'locks', profile + '.json');
  const lockInput = await captureInput(lockPath);
  const lock = parseLock(lockInput.bytes, profile);
  const initInput = await captureInput(join(directory, 'validate.groovy'));
  const inspectorInput = await captureInput(join(directory, 'inspect-sockets.mjs'));
  const configInput = await captureInput(join(directory, 'config.xml'));
  await verifyInputs(lock, cache);
  await mkdir(evidence, { recursive: true, mode: 0o700 });
  await diskGuard(cache, evidence);
  const controlsInput = await captureInput(join(directory, 'controls.json'));
  const cases = JSON.parse(controlsInput.bytes.toString('utf8'));
  const fixtures = [];
  for (const control of cases) {
    if (!/^[a-z-]+\.Jenkinsfile$/.test(control.file)) throw Error('invalid control filename');
    const source = await readFile(join(directory, 'fixtures', control.file), 'utf8');
    fixtures.push({ ...control, source, sha256: digest(source) });
  }
  const report = await withHome(join(evidence, 'homes'), async (home, signal) => {
    await mkdir(join(home, 'tmp'));
    await mkdir(join(home, 'plugins'));
    await mkdir(join(home, 'init.groovy.d'));
    const war = join(home, 'jenkins.war');
    await copyFile(join(cache, lock.core.file), war);
    await verifyArtifact(war, lock.core.sha256);
    for (const plugin of lock.plugins) {
      signal.throwIfAborted();
      const installed = join(home, 'plugins', plugin.id + '.jpi');
      await copyFile(join(cache, plugin.file), installed);
      await verifyArtifact(installed, plugin.sha256);
    }
    await stageInput(join(home, 'init.groovy.d', 'validate.groovy'), initInput);
    await stageInput(join(home, 'config.xml'), configInput);
    await stageInput(join(home, 'inspect-sockets.mjs'), inspectorInput);
    await writeFile(join(home, 'request.json'), JSON.stringify({ lock, fixtures, node: process.execPath }), { mode: 0o600 });
    const cleanEnv = {
      PATH: process.env.PATH, LANG: 'en_US.UTF-8', HOME: home, JENKINS_HOME: home,
      JAVA_HOME: dirname(dirname(java)), TMPDIR: home,
    };
    await runProcess(java, [
      '-Xmx1024m', '-XX:MaxMetaspaceSize=384m', '-Djava.awt.headless=true',
      '-Duser.home=' + home, '-Djava.io.tmpdir=' + join(home, 'tmp'),
      '-XX:ErrorFile=' + join(evidence, profile + '-crash-%p.log'), '-XX:-CreateCoredumpOnCrash',
      '-Djenkins.install.runSetupWizard=false', '-Dhudson.model.UpdateCenter.never=true',
      '-Dhudson.model.UsageStatistics.disabled=true',
      // Fresh-home tool catalogs have mtime zero: defer their default due time beyond this bounded run.
      '-Dhudson.model.DownloadService$Downloadable.defaultInterval=' + (Date.now() + 60 * 60 * 1000),
      '-Dhudson.cli.CLIAction.ALLOW_WEBSOCKET=false',
      '-jar', war, '--httpListenAddress=127.0.0.1', '--httpPort=-1',
      '--httpsListenAddress=127.0.0.1', '--httpsPort=-1', '--http2ListenAddress=127.0.0.1', '--http2Port=-1', '--controlPort=-1',
      '--webroot=' + join(home, 'war'), '--extractedFilesFolder=' + join(home, 'tmp'),
    ], { timeout: 240000, env: cleanEnv, cwd: home, log: join(evidence, profile + '.log'), signal, guard: () => diskGuard(cache, evidence) });
    const result = await json(join(home, 'result.json'));
    if (!result.ok || result.core !== lock.core.version || result.results.length !== fixtures.length) throw Error('oracle failed: invalid result');
    for (const [index, fixture] of fixtures.entries()) {
      const resultCase = result.results[index];
      if (resultCase.id !== fixture.id || resultCase.sha256 !== fixture.sha256 || resultCase.actual !== fixture.expected) throw Error('oracle control mismatch: ' + fixture.id);
    }
    if (result.socketChecks?.length !== 2 || result.socketChecks.some(check => check.internetSockets !== 0)) throw Error('socket inspection missing');
    return { ...result, inspectorSha256: inspectorInput.sha256, lockSha256: lockInput.sha256, initSha256: initInput.sha256, configSha256: configInput.sha256, controlsSha256: controlsInput.sha256, cleanup: 'owned home removed' };
  });
  await writeFile(join(evidence, profile + '.json'), JSON.stringify(report, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  return report;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [command, profile, cache, evidence, java] = process.argv.slice(2);
  try {
    if (command !== 'run' || !['current', 'legacy'].includes(profile) || !cache || !evidence || !java) {
      throw Error('usage: node oracle.mjs run current|legacy CACHE NEW-EVIDENCE-DIR JAVA');
    }
    const result = await oracle(profile, resolve(cache), resolve(evidence), resolve(java));
    console.log(JSON.stringify({ ok: result.ok, profile, controls: result.results.length, plugins: result.plugins.length, cleanup: result.cleanup }));
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
