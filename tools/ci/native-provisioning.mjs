import { isUtf8 } from 'node:buffer';
import { spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { chmodSync, lstatSync, mkdtempSync, readdirSync, realpathSync, writeFileSync } from 'node:fs';
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { collectDiagnostics } from './check-gradle-diagnostics.mjs';
import { readEvidenceBytes, readEvidenceText } from './read-evidence.mjs';
import * as windowsPrivateRoot from './windows-private-root.mjs';

const KOTLIN_VERSION = '2.3.0';
const PREFLIGHT = 'native-provisioning-preflight.json';
const PRELIMINARY = 'native-provisioning-unapproved-diagnostics.json';
const VERIFICATION = 'native-provisioning-verification.json';
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;
const HOSTS = {
  linuxX64: { platform: 'linux', arch: 'x64', bundlePlatform: 'linux-x86_64', producer: 'launcher-linux-x64' },
  macosArm64: { platform: 'darwin', arch: 'arm64', bundlePlatform: 'macos-aarch64', producer: 'launcher-macos-arm64' },
  mingwX64: { platform: 'win32', arch: 'x64', bundlePlatform: 'windows-x86_64', producer: 'launcher-windows-x64' },
};
// Raw Windows security/file identities remain in process memory, bound to the exact held plan.
const privateWindowsPlans = new WeakMap();
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const safePath = value => typeof value === 'string' && value.length <= 4096 && isAbsolute(value) && !/[\x00-\x1f\x7f-\x9f\u2028\u2029]/.test(value);
const inside = (parent, child) => {
  const suffix = relative(parent, child);
  return suffix === '' || (suffix !== '..' && !suffix.startsWith('..' + sep) && !isAbsolute(suffix));
};
function immutableJson(path, value) {
  writeFileSync(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx', mode: 0o400 });
  // Windows chmod's write bit maps to READONLY, not POSIX ownership or ACL protection.
  // No-replace + read-only + bound hashes are logical immutability, not protection from the same SID/admin.
  if (process.platform === 'win32') chmodSync(path, 0o444);
}

function identity(value, checkoutSha, invocationId) {
  if (!record(value) || value.schemaVersion !== 1 || value.command !== 'gradle' ||
      !UUID.test(invocationId ?? '') || value.invocationId !== invocationId ||
      ![value.headSha, value.testedSha].every(sha => typeof sha === 'string' && /^[a-f0-9]{40}$/.test(sha)) ||
      value.testedSha !== checkoutSha || typeof value.runId !== 'string' || !/^[1-9][0-9]{0,15}$/.test(value.runId) ||
      !Number.isSafeInteger(Number(value.runId)) || !Number.isSafeInteger(value.runAttempt) || value.runAttempt < 1) throw new Error();
  const { schemaVersion, headSha, testedSha, runId, runAttempt, command } = value;
  return { schemaVersion, headSha, testedSha, runId, runAttempt, invocationId, command };
}

export function readPinnedKotlinVersion(text) {
  try {
    if (typeof text !== 'string' || text.length > 64 * 1024 || text.includes('\0')) throw new Error();
    let inVersions = false;
    let sections = 0;
    const versions = [];
    for (const line of text.split(/\r?\n/)) {
      if (/^\s*\[/.test(line)) {
        inVersions = /^\s*\[versions\]\s*(?:#.*)?$/.test(line);
        if (inVersions) sections++;
      } else if (inVersions && /^\s*kotlin\s*=/.test(line)) versions.push(line.match(/^\s*kotlin\s*=\s*"([^"]+)"\s*(?:#.*)?$/)?.[1]);
    }
    if (sections !== 1 || versions.length !== 1 || versions[0] !== KOTLIN_VERSION) throw new Error();
    return versions[0];
  } catch { throw new Error('Invalid Native provisioning preparation'); }
}

function ownedDirectory(path, privateMode = false, windowsState = null) {
  const info = lstatSync(path);
  if (!info.isDirectory() || info.isSymbolicLink() || realpathSync(path) !== path) throw new Error();
  if (process.platform === 'win32') {
    if (privateMode) throw new Error(); // Root privacy is established by the native same-handle backend.
    if (windowsState) windowsPrivateRoot.inspectWindowsPrivateChild(path, { ...windowsState.options, type: 'directory', rootIdentity: windowsState.identity });
    return info; // Existing source/temp/producer directories are trusted CI inputs, not private roots.
  }
  if (info.uid !== process.getuid() || (info.mode & 0o022) !== 0 ||
      (privateMode && (info.mode & 0o777) !== 0o700) || !Number.isSafeInteger(info.dev) || !Number.isSafeInteger(info.ino)) throw new Error();
  return info;
}

function ownedFile(path, maxBytes, { executable = false, empty = false, immutable = false, windowsState = null } = {}) {
  const info = lstatSync(path);
  if (!info.isFile() || info.isSymbolicLink() || info.nlink !== 1 || info.size > maxBytes || (!empty && info.size === 0)) throw new Error();
  if (process.platform === 'win32') {
    if (immutable && (info.mode & 0o222) !== 0) throw new Error(); // READONLY mapping only; not an ACL assertion.
    if (windowsState) {
      const native = windowsPrivateRoot.inspectWindowsPrivateChild(path, { ...windowsState.options, type: 'file', rootIdentity: windowsState.identity });
      const size = BigInt('0x' + native.sizeBytes);
      if (native.linkCount !== 1 || size > BigInt(maxBytes) || size !== BigInt(info.size)) throw new Error();
    }
    return info;
  }
  if (info.uid !== process.getuid() || (info.mode & 0o022) !== 0 || (immutable && (info.mode & 0o777) !== 0o400) ||
      (executable && (info.mode & 0o100) === 0)) throw new Error();
  return info;
}

function windowsIdentityDigest(identity) {
  return hash(JSON.stringify({ fileId: identity.fileId, ownerSid: identity.ownerSid, volumeSerial: identity.volumeSerial }));
}

function freshRoot(checked, io) {
  if (checked.host.platform !== 'win32') {
    const path = mkdtempSync(join(checked.runnerTemp, 'gvy-native-provisioning-'));
    const info = ownedDirectory(path, true);
    return { publicIdentity: { path, uid: info.uid, device: String(info.dev), inode: String(info.ino) }, state: null };
  }
  if (process.platform !== 'win32') throw new Error();
  const path = join(checked.runnerTemp, 'gvy-native-provisioning-' + randomUUID().replaceAll('-', ''));
  const options = { helperPath: io.windowsHelperPath ?? process.env.GVY_CI_PRIVATE_ROOT_HELPER };
  const identity = windowsPrivateRoot.createWindowsPrivateRoot(path, options);
  return { publicIdentity: { path, identitySha256: windowsIdentityDigest(identity) }, state: Object.freeze({ identity, options: Object.freeze(options) }) };
}

function readOwnedJson(path, maxBytes, immutable = false) {
  ownedFile(path, maxBytes, { immutable });
  return JSON.parse(readEvidenceText(path, maxBytes));
}

function checkoutSha(sourceRoot) {
  const result = spawnSync('git', ['-C', sourceRoot, 'rev-parse', 'HEAD'], { encoding: 'utf8', maxBuffer: 4096, timeout: 5000 });
  if (result.status !== 0) throw new Error();
  return result.stdout.trim();
}

function checkedOptions(options, io) {
  const host = HOSTS[options.target];
  if (!host || host.platform !== (options.platform ?? process.platform) || host.arch !== (options.arch ?? process.arch) || options.producer !== host.producer ||
      (host.platform !== 'win32' && (!Number.isInteger(process.getuid?.()) || process.getuid() <= 0)) || ![options.runnerTemp, options.sourceRoot, options.producerDir].every(safePath)) throw new Error();
  if ([options.runnerTemp, options.sourceRoot, options.producerDir].some(path => lstatSync(path).isSymbolicLink())) throw new Error();
  const runnerTemp = realpathSync(options.runnerTemp), sourceRoot = realpathSync(options.sourceRoot), producerDir = realpathSync(options.producerDir);
  if (host.platform === 'win32' && [options.runnerTemp, options.sourceRoot].some(path => !path.isWellFormed() || realpathSync(path).toLowerCase() !== resolve(path).toLowerCase())) throw new Error();
  // Resolve the trusted runner root, but never allow a report path component to redirect its namespace.
  if (!inside(resolve(options.runnerTemp), resolve(options.producerDir)) ||
      producerDir !== join(runnerTemp, relative(resolve(options.runnerTemp), resolve(options.producerDir)))) throw new Error();
  for (const directory of [runnerTemp, sourceRoot, producerDir, dirname(producerDir), join(sourceRoot, 'gradle')]) ownedDirectory(directory);
  if (inside(sourceRoot, runnerTemp) || inside(runnerTemp, sourceRoot) || !inside(runnerTemp, producerDir) || dirname(producerDir) === runnerTemp ||
      inside(sourceRoot, producerDir) || basename(producerDir) !== `${host.producer}-${options.invocationId}`) throw new Error();
  const context = identity(options.context, (io.checkoutSha ?? checkoutSha)(sourceRoot), options.invocationId);
  const catalog = join(sourceRoot, 'gradle/libs.versions.toml');
  ownedFile(catalog, 64 * 1024);
  readPinnedKotlinVersion(readEvidenceText(catalog, 64 * 1024));
  const siblings = readdirSync(dirname(producerDir)).filter(name => name.startsWith(host.producer + '-'));
  if (siblings.length !== 1 || siblings[0] !== basename(producerDir)) throw new Error();
  return { context, host, runnerTemp, sourceRoot, producerDir };
}

/** Called by the wrapper before capture. Does not write a compiler, marker, or environment file. */
export function prepareNativeProvisioning(options, io = {}) {
  try {
    const checked = checkedOptions(options, io);
    if (!isDeepStrictEqual(readdirSync(checked.producerDir), ['context.json']) ||
        !isDeepStrictEqual(readOwnedJson(join(checked.producerDir, 'context.json'), 16 * 1024), checked.context)) throw new Error();
    const created = freshRoot(checked, io);
    const rootPath = created.publicIdentity.path;
    if (readdirSync(rootPath).length !== 0 || inside(dirname(checked.producerDir), rootPath)) throw new Error();
    const dataDir = join(rootPath, '.konan');
    const preflight = {
      schemaVersion: 1, kind: 'native-provisioning-preflight', context: checked.context, invocationId: options.invocationId,
      target: options.target, host: { platform: checked.host.platform, arch: checked.host.arch }, producer: checked.host.producer,
      kotlinVersion: KOTLIN_VERSION, preparedAt: new Date().toISOString(),
      freshRoot: created.publicIdentity,
      konanDataDir: dataDir, expectedBundleHome: join(dataDir, `kotlin-native-prebuilt-${checked.host.bundlePlatform}-${KOTLIN_VERSION}`),
      initialKonanDataDirAbsent: true,
      contextSha256: hash(readEvidenceBytes(join(checked.producerDir, 'context.json'), 16 * 1024)),
    };
    const preflightPath = join(checked.producerDir, PREFLIGHT);
    immutableJson(preflightPath, preflight);
    const plan = {
      schemaVersion: 1, context: checked.context, invocationId: options.invocationId, producer: checked.host.producer, target: options.target,
      platform: checked.host.platform, arch: checked.host.arch, sourceRoot: checked.sourceRoot, runnerTemp: checked.runnerTemp, producerDir: checked.producerDir,
      preflight, preflightPath, preflightSha256: hash(readEvidenceBytes(preflightPath, 16 * 1024)),
      childEnv: { KONAN_DATA_DIR: dataDir }, gradleArgs: [`-Pkonan.data.dir=${dataDir}`],
    };
    if (created.state) privateWindowsPlans.set(plan, created.state);
    return plan;
  } catch { throw new Error('Invalid Native provisioning preparation'); }
}

function verifyPreflight(checked, plan) {
  const path = join(checked.producerDir, PREFLIGHT), preflight = readOwnedJson(path, 16 * 1024, true);
  if (plan.schemaVersion !== 1 || plan.preflightPath !== path || !/^[a-f0-9]{64}$/.test(plan.preflightSha256 ?? '') ||
      hash(readEvidenceBytes(path, 16 * 1024)) !== plan.preflightSha256 || !isDeepStrictEqual(plan.preflight, preflight) ||
      preflight.schemaVersion !== 1 || preflight.kind !== 'native-provisioning-preflight' || !isDeepStrictEqual(preflight.context, checked.context) ||
      preflight.invocationId !== checked.context.invocationId || preflight.target !== plan.target ||
      !isDeepStrictEqual(preflight.host, { platform: checked.host.platform, arch: checked.host.arch }) ||
      preflight.producer !== checked.host.producer || preflight.kotlinVersion !== KOTLIN_VERSION || preflight.initialKonanDataDirAbsent !== true ||
      !record(preflight.freshRoot) || !safePath(preflight.freshRoot.path) || dirname(preflight.freshRoot.path) !== checked.runnerTemp ||
      !(checked.host.platform === 'win32' ? /^gvy-native-provisioning-[a-f0-9]{32}$/ : /^gvy-native-provisioning-[A-Za-z0-9]{6}$/).test(basename(preflight.freshRoot.path)) ||
      !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(preflight.preparedAt ?? '')) throw new Error();
  const windowsState = privateWindowsPlans.get(plan);
  if (checked.host.platform === 'win32') {
    if (!windowsState || Object.keys(preflight.freshRoot).sort().join(',') !== 'identitySha256,path') throw new Error();
    const actual = windowsPrivateRoot.verifyWindowsPrivateRoot(preflight.freshRoot.path, windowsState.identity, windowsState.options);
    if (preflight.freshRoot.identitySha256 !== windowsIdentityDigest(actual)) throw new Error();
  } else {
    const root = ownedDirectory(preflight.freshRoot.path, true);
    if (root.uid !== preflight.freshRoot.uid || String(root.dev) !== preflight.freshRoot.device || String(root.ino) !== preflight.freshRoot.inode) throw new Error();
  }
  const dataDir = join(preflight.freshRoot.path, '.konan');
  const home = join(dataDir, `kotlin-native-prebuilt-${checked.host.bundlePlatform}-${KOTLIN_VERSION}`);
  if (!isDeepStrictEqual(plan.childEnv, { KONAN_DATA_DIR: dataDir }) || !isDeepStrictEqual(plan.gradleArgs, [`-Pkonan.data.dir=${dataDir}`]) ||
      preflight.konanDataDir !== dataDir || preflight.expectedBundleHome !== home || inside(dirname(checked.producerDir), preflight.freshRoot.path)) throw new Error();
  for (const directory of [dataDir, home, join(home, 'bin'), join(home, 'konan'), join(home, 'konan', 'lib')]) ownedDirectory(directory, false, windowsState);
  const contextPath = join(checked.producerDir, 'context.json');
  if (!isDeepStrictEqual(readOwnedJson(contextPath, 16 * 1024), checked.context) ||
      hash(readEvidenceBytes(contextPath, 16 * 1024)) !== preflight.contextSha256) throw new Error();
  return preflight;
}

function verifyCapture(checked, preflight, rawBytes, preliminaryDiagnostics) {
  if (!Buffer.isBuffer(rawBytes) || rawBytes.length === 0 || rawBytes.length > 128 * 1024 * 1024 || !isUtf8(rawBytes)) throw new Error();
  const consolePath = join(checked.producerDir, 'console.log');
  ownedFile(consolePath, 128 * 1024 * 1024);
  if (!readEvidenceBytes(consolePath, 128 * 1024 * 1024).equals(rawBytes)) throw new Error();
  const pendingBytes = Buffer.from(JSON.stringify(preliminaryDiagnostics, null, 2) + '\n');
  if (pendingBytes.length > 2 * 1024 * 1024) throw new Error();
  const report = JSON.parse(pendingBytes.toString('utf8'));
  immutableJson(join(checked.producerDir, PRELIMINARY), report);
  const raw = rawBytes.toString('utf8'), plain = raw.replaceAll('\r\n', '\n');
  const markers = [...plain.matchAll(/^BUILD (SUCCESSFUL|FAILED) in .+$/gm)];
  const expectedWarning = `Kotlin/Native bundle directory ${preflight.expectedBundleHome} is not empty. Native bundle files will be overwritten.`;
  const warnings = plain.split('\n').filter(line => /Kotlin\/Native.*bundle.*directory|Native bundle files.*overwritten/i.test(line));
  if (warnings.length > 1 || warnings.some(line => line.trim() !== expectedWarning)) throw new Error();
  const eligibleDiagnostics = warnings.length ? [`[emitter kotlin-native bundle provisioning] Kotlin/Native bundle directory <konan>/kotlin-native-prebuilt-${checked.host.bundlePlatform}-${KOTLIN_VERSION} is not empty. Native bundle files will be overwritten.`] : [];
  if (report.schemaVersion !== 1 || report.producer !== checked.host.producer || !isDeepStrictEqual(report.context, checked.context) ||
      report.complete !== true || typeof report.ok !== 'boolean' || report.commandExitCode !== 0 || report.captureExitCode !== 0 || report.commandSignal !== null ||
      !Array.isArray(report.errors) || report.errors.length !== 0 || !isDeepStrictEqual(report.unexpected, eligibleDiagnostics) || report.ok !== (eligibleDiagnostics.length === 0) ||
      !isDeepStrictEqual(report.log, { bytes: rawBytes.length, sha256: hash(rawBytes) }) ||
      !isDeepStrictEqual(report.diagnostics, collectDiagnostics(raw, { rootDir: checked.sourceRoot })) ||
      !plain.endsWith('\n') || /[\x00\x1b\r]/.test(plain) || markers.length !== 1 || markers[0][1] !== 'SUCCESSFUL' ||
      /^> (?:Task|Configure project) /m.test(plain.slice(markers[0].index + markers[0][0].length))) throw new Error();
  return {
    eligibleDiagnostics, provisioningWarningCount: warnings.length,
    preliminaryDiagnosticsSha256: hash(pendingBytes),
    wrapper: { invocationId: checked.context.invocationId, complete: true, commandExitCode: 0, captureExitCode: 0,
      diagnosticGateOk: report.ok, log: report.log, contextSha256: preflight.contextSha256 },
  };
}

/** Eligibility only: the parent still owns normal diagnostic evaluation and reviewed expiry metadata. */
export function verifyNativeProvisioning({ plan, context, producer, rawBytes, preliminaryDiagnostics }, io = {}) {
  try {
    const checked = checkedOptions(plan, io);
    if (!isDeepStrictEqual(context, checked.context) || producer !== checked.host.producer) throw new Error();
    const preflight = verifyPreflight(checked, plan);
    const observed = verifyCapture(checked, preflight, rawBytes, preliminaryDiagnostics);
    const home = preflight.expectedBundleHome;
    const windowsState = privateWindowsPlans.get(plan);
    const marker = ownedFile(join(home, 'provisioned.ok'), 4096, { empty: true, windowsState });
    if (marker.size !== 0) throw new Error();
    const compilerFiles = [
      [windowsState ? 'bin/konanc.bat' : 'bin/konanc', 256 * 1024, !windowsState],
      [windowsState ? 'bin/kotlinc-native.bat' : 'bin/kotlinc-native', 256 * 1024, !windowsState],
      ['konan/konan.properties', 128 * 1024, false], ['konan/lib/kotlin-native-compiler-embeddable.jar', 256 * 1024 * 1024, false],
    ].map(([relativePath, limit, executable]) => {
      const path = join(home, relativePath), info = ownedFile(path, limit, { executable, windowsState });
      return { relativePath, bytes: info.size, sha256: hash(readEvidenceBytes(path, limit)), ...(windowsState ? { executableBitRequired: false } : { executable }) };
    });
    if (windowsState) windowsPrivateRoot.verifyWindowsPrivateRoot(preflight.freshRoot.path, windowsState.identity, windowsState.options);
    const verification = {
      schemaVersion: 1, kind: 'native-provisioning-verification', ok: true, complete: true,
      context: checked.context, invocationId: checked.context.invocationId, target: plan.target, producer: checked.host.producer, kotlinVersion: KOTLIN_VERSION,
      preflightSha256: plan.preflightSha256, freshRoot: preflight.freshRoot, konanDataDir: preflight.konanDataDir, expectedBundleHome: home,
      marker: { relativePath: 'provisioned.ok', bytes: marker.size }, compilerFiles, ...observed,
      limits: ['A fresh private namespace proves containment, not observed lock-only contents at the copy boundary.',
        'Marker/compiler presence is functional evidence under trusted pinned KGP installation, not full compiler integrity.',
        'No diagnostic exception is adopted; native unit and real-JAR CLI/LSP tests remain required.',
        ...(windowsState ? ['Windows public receipts use exclusive creation, READONLY and hashes; the same SID or privileged actors can still replace them.',
          'Raw Windows SID and full file/volume identities stay only in the held invocation plan; public receipts contain a digest.'] : [])],
    };
    immutableJson(join(checked.producerDir, VERIFICATION), verification);
    return verification;
  } catch { throw new Error('Invalid Native provisioning evidence'); }
}
