import { isUtf8 } from 'node:buffer';
import { createHash } from 'node:crypto';
import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluate } from './bazel-diagnostics.mjs';

export const schemaVersion = 1;

export function validateNativeDiagnosticPolicy(entries, now = new Date()) {
  evaluate([], entries, now);
  const expected = ['linux-x86_64', 'macos-aarch64', 'windows-x86_64'].map(platform =>
    `[emitter kotlin-native bundle provisioning] Kotlin/Native bundle directory <konan>/kotlin-native-prebuilt-${platform}-2.3.0 is not empty. Native bundle files will be overwritten.`);
  const fields = ['diagnostic', 'owner', 'issue', 'reason', 'nextAction', 'acceptance', 'expires', 'requiresProof'];
  if (entries.length !== 3 || entries.some(entry => entry.requiresProof !== 'native-provisioning-v1' ||
      Object.keys(entry).some(key => !fields.includes(key))) ||
      !expected.every(diagnostic => entries.some(entry => entry.diagnostic === diagnostic))) {
    throw new Error('Invalid Native conditional diagnostic policy');
  }
  return entries;
}

export function loadNativeDiagnosticPolicy(now = new Date()) {
  return validateNativeDiagnosticPolicy(JSON.parse(readFileSync(new URL('./native-provisioning-diagnostics.json', import.meta.url), 'utf8')), now);
}

function plain(log) {
  return log.replace(/\x1b\[[0-9;]*m/g, '').replaceAll('\r\n', '\n');
}

// Gradle 9.8.2 HealthExpirationStrategy and DaemonStateCoordinator emit these
// operational warnings without a warning prefix or a reliable parallel task.
// https://github.com/gradle/gradle/blob/v9.8.2/platforms/core-runtime/launcher/src/main/java/org/gradle/launcher/daemon/server/health/HealthExpirationStrategy.java#L102
const memoryReason = '(?:(?:after running out of JVM heap space|since the JVM garbage collector is thrashing)(?: and after running out of JVM Metaspace)?|after running out of JVM Metaspace)';
const daemonMemoryExpiry = new RegExp(`^(?:The Daemon will expire (?:after the build|immediately) ${memoryReason}\\.|Daemon (?:will be stopped at the end of the build|is stopping immediately) ${memoryReason})$`);
const memorySize = "(?:unknown(?: size)?|-?[0-9]+(?:\\.[0-9]+)? (?:B|[KMGTPE]iB))";
const daemonMemorySettings = new RegExp(`^The currently configured max heap space is '${memorySize}' and the configured max metaspace is '${memorySize}'\\.$`);
const daemonMemoryAdvice = new Set([
  'The project memory settings are likely not configured or are configured to an insufficient value.',
  'The daemon will restart for the next build, which may increase subsequent build times.',
  'The memory settings for this project must be adjusted to avoid this failure.',
  "These settings can be adjusted by setting 'org.gradle.jvmargs' in 'gradle.properties'.",
  "To disable this warning, set 'org.gradle.daemon.performance.disable-logging=true'.",
]);
function isDaemonMemoryAdvice(line) {
  return daemonMemoryExpiry.test(line) || daemonMemorySettings.test(line) || daemonMemoryAdvice.has(line) ||
    /^For more information on how to set these values, please refer to https:\/\/docs\.gradle\.org\/[^\s/]+\/userguide\/build_environment\.html#sec:configuring_jvm_memory in the Gradle documentation\.$/.test(line);
}

function deprecationOrigin(message, origin) {
  // NOTE: These Native consumers run outside reliable project/task output. Keep
  // the first real codec caller, not the common Attribute.of validation frame or
  // a deeper codec caller. Exact messages and origins are still policy keys.
  // Historical traces and their source/job hashes are in fixtures/gradle-native-*.
  if (message.startsWith('Using the enum type KotlinNativeBundleArtifactsTypes as an attribute value type has been deprecated.') &&
      /^org\.gradle\.internal\.serialize\.codecs\.dm\.transform\.(?:CalculateArtifactsCodec|DefaultTransformCodec)\.decode\([^()]+:\d+\)$/.test(origin)) {
    return { origin, context: 'emitter gradle configuration-cache' };
  }
  if (/^(?:org\.gradle\.|java\.|java\.base\/|jdk\.|kotlin\.|Program\.)/.test(origin)) return undefined;
  if (message.startsWith('Declaring dependencies using multi-string notation has been deprecated.') &&
      /^org\.jetbrains\.kotlin\.gradle\.targets\.native\.toolchain\.KotlinNativeBundleArtifactFormat\$addKotlinNativeBundleConfiguration\$1\$1\.execute\(KotlinNativeBundleArtifactFormat\.kt:\d+\)$/.test(origin)) {
    return { origin, context: 'emitter kotlin-native bundle configuration' };
  }
  return { origin };
}

// Tool output is separate from test STANDARD_OUT/ERR. Runtime/server diagnostics
// require a harness producer with test/scenario identity and explicit assertions.
export function collectDiagnostics(log, { rootDir = process.cwd() } = {}) {
  const diagnostics = new Set();
  let context = 'gradle';
  let capturedTestOutput = false;
  let pending;
  const flush = () => {
    if (pending) diagnostics.add(`[${pending.context}] ${pending.message}${pending.attribution ?? ''}`);
    pending = undefined;
  };
  const root = rootDir.replaceAll('\\', '/').replace(/\/$/, '');
  for (const raw of plain(log).split('\n')) {
    const header = raw.match(/^> (Configure project|Task) (:\S*)/);
    if (header) {
      flush();
      capturedTestOutput = false;
      context = `${header[1] === 'Task' ? 'task' : 'project'} ${header[2]}`;
      continue;
    }
    // A warning message can itself end in a test-output/status token.
    const warningPrefix = /^(?:w:|warn(?:ing)?(?:[\s:\[]|$)|\[warn(?:ing)?\]|⚠)/i.test(raw);
    const testHeader = warningPrefix ? null : raw.match(/^\S+(?: > .+)? (PASSED|FAILED|SKIPPED|STARTED|STANDARD_OUT|STANDARD_ERROR)$/);
    if (testHeader) {
      flush();
      capturedTestOutput = testHeader[1] === 'STANDARD_OUT' || testHeader[1] === 'STANDARD_ERROR';
      continue;
    }
    if (capturedTestOutput && /^\s/.test(raw)) continue;
    if (raw.trim()) capturedTestOutput = false;
    const line = raw.trim();
    const source = line.match(/^at Build_gradle[^\n]*\(([^():]+\.gradle\.kts:\d+)\)$/);
    if (pending && source) {
      const project = context.startsWith('project ') ? context.slice('project :'.length).replaceAll(':', '/') : '';
      pending.attribution = ` [source ${project ? `${project}/` : ''}${source[1]}]`;
      flush();
      continue;
    }
    const origin = line.match(/^at ([^ ]+\([^()]+\))$/);
    if (pending && /deprecated/.test(pending.message) && origin) {
      const attribution = deprecationOrigin(pending.message, origin[1]);
      if (attribution) {
        pending.context = attribution.context ?? pending.context;
        pending.attribution = ` [origin ${attribution.origin}]`;
        flush();
        continue;
      }
    }
    if (!line) flush();
    const compiler = /^(?:w: |(?:file:\/\/\/)?\S+\.(?:kt|kts|java|groovy):\d+(?::\d+)?:?\s*(?:warning:|[Ww]arning\b))/.test(line);
    // KGP logs this warning without a severity prefix. Its own lock can make a
    // fresh distribution nonempty; retain the warning without guessing contents
    // or attributing an asynchronous emitter to the most recent task header.
    const nativeBundleOverwrite = /^Kotlin\/Native bundle directory .+ is not empty\. Native bundle files will be overwritten\.$/.test(line);
    // Gradle indents captured test output; compiler diagnostics sometimes indent.
    if (/^\s/.test(raw) && !compiler && !nativeBundleOverwrite) continue;
    if (nativeBundleOverwrite) {
      flush();
      const message = line.replaceAll('\\', '/')
        .replace(/^Kotlin\/Native bundle directory .*\/\.konan\/(kotlin-native-prebuilt-[^/]+) is not empty\./,
          'Kotlin/Native bundle directory <konan>/$1 is not empty.');
      diagnostics.add(`[emitter kotlin-native bundle provisioning] ${message}`);
      continue;
    }
    if (isDaemonMemoryAdvice(line)) {
      flush();
      diagnostics.add(`[gradle daemon] ${line}`);
      continue;
    }
    const warning = /\b(?:has been|have been|is|are) deprecated\b|^Deprecated Gradle features were used|\b(?:warning:|WARNING:|WARN:)|^WARN(?:ING)?(?:\s|:)|^\[WARN(?:ING)?\]|^⚠/.test(line);
    if (!compiler && !warning) continue;
    const normalized = line.replaceAll('\\', '/')
      .replaceAll(`file://${root}/`, '')
      .replaceAll(`${root}/`, '')
      .replace(/\s+/g, ' ');
    flush();
    pending = { context, message: normalized };
  }
  flush();
  return [...diagnostics].sort();
}

function validExit(value) {
  return Number.isInteger(value) && value >= 0 && value <= 255;
}

export function checkGradleDiagnostics({ log, commandExitCode, captureExitCode, exceptions, now = new Date(), rootDir, encodingValid = true }) {
  const errors = [];
  const text = typeof log === 'string' ? plain(log) : '';
  const diagnostics = collectDiagnostics(text, { rootDir });
  if (!text.trim()) errors.push('Missing or empty Gradle log');
  if (text && !text.endsWith('\n')) errors.push('Gradle log is truncated (missing final newline)');
  if (!encodingValid || /[\x00\x1b\r]/.test(text)) errors.push('Gradle log contains invalid encoding or non-plain console output');
  if (!validExit(commandExitCode)) errors.push('Missing or invalid original command exit code');
  if (!validExit(captureExitCode) || captureExitCode !== 0) errors.push('Missing or failed log capture exit code');
  const markers = [...text.matchAll(/^BUILD (SUCCESSFUL|FAILED) in .+$/gm)];
  if (markers.length !== 1) errors.push('Expected exactly one final Gradle BUILD SUCCESSFUL/FAILED marker');
  else {
    if ((markers[0][1] === 'SUCCESSFUL') !== (commandExitCode === 0)) {
      errors.push('Gradle completion marker contradicts original command exit code');
    }
    if (/^> (?:Task|Configure project) /m.test(text.slice(markers[0].index + markers[0][0].length))) {
      errors.push('Gradle work appears after the final completion marker');
    }
  }
  const complete = errors.length === 0;
  let policy = { ok: false, unexpected: diagnostics, accepted: [], exceptions: [] };
  try {
    evaluate([], exceptions, now); // Validate all entries, including conditional debt.
    if (exceptions.some(entry => entry.requiresProof !== undefined && entry.requiresProof !== 'native-provisioning-v1')) {
      throw new Error('Unknown conditional proof kind');
    }
    const unconditional = exceptions.filter(entry => entry.requiresProof === undefined);
    policy = { ...evaluate(diagnostics, unconditional, now), exceptions };
  } catch (error) {
    errors.push(`Diagnostic policy: ${error.message}`);
  }
  if (validExit(commandExitCode) && commandExitCode !== 0) errors.push(`Gradle command failed with exit ${commandExitCode}`);
  return {
    schemaVersion, complete,
    commandExitCode: commandExitCode ?? null, captureExitCode: captureExitCode ?? null,
    diagnostics, ...policy, errors,
    // Policy evaluates warning debt only; the producer also requires complete,
    // successful command evidence. Never let an accepted warning hide failure.
    ok: complete && policy.ok && commandExitCode === 0 && errors.length === 0,
  };
}

const escape = value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');

export function renderSummary(result) {
  const debts = new Map();
  for (const entry of result.exceptions) {
    const key = JSON.stringify([entry.owner, entry.issue, entry.reason, entry.nextAction, entry.acceptance, entry.expires]);
    const debt = debts.get(key) ?? { ...entry, count: 0 };
    debt.count++;
    debts.set(key, debt);
  }
  return [
    '## Gradle diagnostic accountability', '',
    `**${result.ok ? 'PASS' : 'FAIL'}** (${escape(result.producer)}): ${result.diagnostics.length} diagnostics; ${result.unexpected.length} unexpected; ${result.accepted.filter(entry => entry.requiresProof).length} accepted with Native proof; evidence ${result.complete ? 'complete' : 'incomplete'}.`, '',
    ...(result.nativeProvisioning ? [`Native provisioning proof: ${result.nativeProvisioning.complete ? 'verified' : 'failed'}.`, ''] : []),
    `Original command exit: ${result.commandExitCode}; log capture exit: ${result.captureExitCode}.`, '',
    ...(result.memoryProbe ? [
      `Required numeric daemon memory observation: ${result.memoryProbe.complete ? 'complete' : 'incomplete'} (memory.json). Sampled maxima are observations, not guaranteed peaks.`, '',
    ] : []),
    ...result.errors.map(value => `- ${escape(value)}`), '',
    ...result.unexpected.map(value => `<pre>${escape(value)}</pre>`), '',
    '### Tracked debt (including exceptions absent from cached output)', '',
    ...[...debts.values()].map(entry => `- ${entry.count} exact exceptions: [${escape(entry.owner)}](${entry.issue}), expires ${entry.expires}: ${escape(entry.reason)} Next: ${escape(entry.nextAction)} Acceptance: ${escape(entry.acceptance)}`), '',
    '<details><summary>Exact exception fingerprints</summary>', '',
    ...result.exceptions.map(entry => `<pre>${escape(entry.diagnostic)}</pre>`), '', '</details>', '',
    'Raw tool logs are retained with this report. Passing-test runtime/server output requires its separate harness producer; see docs/ci-diagnostics.md.', '',
  ].join('\n');
}

function parseArgs(args) {
  const allowed = new Set(['log', 'exit-code', 'capture-exit-code', 'producer', 'baseline', 'report', 'root']);
  const values = {};
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index].replace(/^--/, '');
    if (!args[index].startsWith('--') || !allowed.has(key) || values[key] !== undefined || !args[index + 1]) {
      throw new Error(`Invalid or duplicate option: ${args[index]}`);
    }
    values[key] = args[index + 1];
  }
  for (const key of ['log', 'exit-code', 'capture-exit-code', 'producer', 'baseline', 'report']) {
    if (!values[key]) throw new Error(`Required option: --${key}`);
  }
  if (!/^[a-z0-9][a-z0-9._/-]*$/.test(values.producer)) throw new Error('Producer requires a stable lowercase identifier');
  return values;
}

function main(args) {
  const options = parseArgs(args);
  mkdirSync(options.report, { recursive: true });
  const errors = [];
  let log;
  let bytes;
  let exceptions;
  try {
    bytes = readFileSync(options.log);
    log = bytes.toString('utf8');
  } catch (error) { errors.push(`Cannot read Gradle log: ${error.code ?? error.message}`); }
  try { exceptions = JSON.parse(readFileSync(options.baseline, 'utf8')); }
  catch (error) { errors.push(`Cannot read diagnostic policy: ${error.message}`); }
  try { exceptions = [...exceptions, ...loadNativeDiagnosticPolicy()]; }
  catch (error) { errors.push(`Cannot read Native conditional diagnostic policy: ${error.message}`); }
  const exitCode = value => /^\d+$/.test(value) ? Number(value) : undefined;
  const result = checkGradleDiagnostics({
    log, commandExitCode: exitCode(options['exit-code']), captureExitCode: exitCode(options['capture-exit-code']),
    exceptions, rootDir: options.root, encodingValid: bytes ? isUtf8(bytes) : false,
  });
  result.producer = options.producer;
  result.log = bytes ? { bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') } : null;
  if (errors.length) {
    result.ok = false;
    result.errors.push(...errors);
  }
  // A failed/missing producer cannot leave stale successful evidence behind.
  writeFileSync(resolve(options.report, 'raw.log'), bytes ?? '');
  const summary = renderSummary(result);
  writeFileSync(resolve(options.report, 'summary.md'), summary);
  writeFileSync(resolve(options.report, 'diagnostics.json'), `${JSON.stringify(result, null, 2)}\n`);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
  console.log(summary);
  if (!result.ok) process.exitCode = 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(process.argv.slice(2)); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
