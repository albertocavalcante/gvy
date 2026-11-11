// Bazel may serialize client environment into BEP. The parent derives invocation
// context first; the child receives only these platform/tool paths and identity.
// Credential variables, Java option injection and GitHub context never inherit.
export function bazelChildEnvironment(parent) {
  const allowed = [
    'PATH', 'HOME', 'USER', 'LOGNAME', 'TMPDIR', 'TMP', 'TEMP',
    'JAVA_HOME', 'BAZELISK_HOME', 'SDKROOT', 'DEVELOPER_DIR',
    'SYSTEMROOT', 'SystemRoot', 'WINDIR', 'COMSPEC', 'PATHEXT',
    'LOCALAPPDATA', 'APPDATA', 'PROGRAMFILES', 'PROGRAMFILES(X86)',
    'PROCESSOR_ARCHITECTURE', 'NUMBER_OF_PROCESSORS',
  ];
  return Object.fromEntries(allowed.filter(key => typeof parent[key] === 'string').map(key => [key, parent[key]]));
}

import { createHash } from 'node:crypto';
import { isUtf8 } from 'node:buffer';
import { gunzipSync } from 'node:zlib';

export const PUBLIC_CONTRACT = 'gvy-public-bazel-v2';
export const MAX_BEP_BYTES = 256 * 1024 * 1024;
export const MAX_PROFILE_BYTES = 128 * 1024 * 1024;
export const MAX_COMPRESSED_PROFILE_BYTES = 32 * 1024 * 1024;
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const opaquePattern = /^opaque:[a-f0-9]{64}$/;
const metadataFields = { HEAD_SHA: 'headSha', TESTED_SHA: 'testedSha', RUN_ID: 'runId', RUN_ATTEMPT: 'runAttempt' };
const testStatuses = ['NO_STATUS', 'PASSED', 'FLAKY', 'TIMEOUT', 'FAILED', 'INCOMPLETE', 'REMOTE_FAILURE', 'FAILED_TO_BUILD', 'TOOL_HALTED_BEFORE_TESTING'];
const abortReasons = ['UNKNOWN', 'USER_INTERRUPTED', 'NO_ANALYZE', 'NO_BUILD', 'TIME_OUT', 'REMOTE_ENVIRONMENT_FAILURE', 'INTERNAL', 'LOADING_FAILURE', 'ANALYSIS_FAILURE', 'SKIPPED', 'INCOMPLETE', 'OUT_OF_MEMORY'];
export const digest = bytes => createHash('sha256').update(bytes).digest('hex');
function opaque(value, publicInput = false) {
  if (typeof value !== 'string' || value.length > 16384) throw new Error('Invalid BEP string identifier');
  if (publicInput) {
    if (!opaquePattern.test(value)) throw new Error('Noncanonical public identifier');
    return value;
  }
  // JSON string encoding preserves UTF-16 code units, including escaped lone
  // surrogates that UTF-8 encoding would otherwise replace with the same byte.
  return `opaque:${digest(JSON.stringify(value))}`;
}
function numeric(value) {
  if ((typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= Number.MAX_SAFE_INTEGER) || (typeof value === 'string' && /^\d{1,20}$/.test(value))) return value;
  throw new Error('Invalid numeric evidence field');
}
function boolean(value) {
  if (typeof value !== 'boolean') throw new Error('Invalid boolean evidence field');
  return value;
}
function duration(value) {
  if (typeof value !== 'string' || !/^\d{1,18}(?:\.\d{1,9})?s$/.test(value)) throw new Error('Invalid duration evidence field');
  return value;
}
function timestamp(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) || !Number.isFinite(Date.parse(value))) throw new Error('Invalid timestamp evidence field');
  return value;
}
function enumeration(values) {
  return value => { if (!values.includes(value)) throw new Error('Invalid evidence enum'); return value; };
}
function fields(value, schema) {
  if (!object(value)) throw new Error('Invalid evidence object');
  return Object.fromEntries(Object.entries(schema).filter(([key]) => Object.hasOwn(value, key)).map(([key, convert]) => [key, convert(value[key])]));
}
function identifierFields(value, schema) {
  if (!object(value) || Object.keys(value).some(key => !Object.hasOwn(schema, key))) throw new Error('Unknown BEP identifier field');
  return fields(value, schema);
}
function array(convert) {
  return value => {
    if (!Array.isArray(value) || value.length > 10000) throw new Error('Evidence array exceeds contract');
    return value.map(convert);
  };
}
function identifierSchemas(identifier) {
  const configurationId = value => identifierFields(value, { id: identifier });
  return {
    unknown: { details: identifier }, progress: { opaqueCount: numeric }, started: {}, unstructuredCommandLine: {},
    structuredCommandLine: { commandLineLabel: identifier }, workspaceStatus: {}, optionsParsed: {},
    fetch: { url: identifier, downloader: enumeration(['UNKNOWN', 'HTTP', 'GRPC']) },
    configuration: { id: identifier }, targetConfigured: { label: identifier, aspect: identifier },
    pattern: { pattern: array(identifier) }, patternSkipped: { pattern: array(identifier) }, namedSet: { id: identifier },
    targetCompleted: { label: identifier, aspect: identifier, configuration: configurationId },
    actionCompleted: { primaryOutput: identifier, label: identifier, configuration: configurationId },
    unconfiguredLabel: { label: identifier }, configuredLabel: { label: identifier, configuration: configurationId },
    testResult: { label: identifier, configuration: configurationId, run: numeric, shard: numeric, attempt: numeric },
    testProgress: { label: identifier, configuration: configurationId, run: numeric, shard: numeric, attempt: numeric, opaqueCount: numeric },
    testSummary: { label: identifier, configuration: configurationId }, targetSummary: { label: identifier, configuration: configurationId },
    buildFinished: {}, buildToolLogs: {}, buildMetrics: {}, workspace: {}, buildMetadata: {}, convenienceSymlinksIdentified: {}, execRequest: {},
  };
}
function projectId(id, schemas) {
  if (!object(id) || Object.keys(id).length !== 1) throw new Error('Invalid BEP identifier');
  const kind = Object.keys(id)[0];
  if (!Object.hasOwn(schemas, kind) || !object(id[kind])) throw new Error('Unsupported BEP identifier');
  const schema = schemas[kind];
  return { [kind]: identifierFields(id[kind], schema) };
}
const counts = names => Object.fromEntries(names.split(' ').filter(Boolean).map(name => [name, numeric]));
function metrics(value, identifier) {
  return fields(value, {
    actionSummary: entry => fields(entry, {
      ...counts('actionsCreated actionsExecuted actionsCreatedNotIncludingAspects remoteCacheHits'),
      actionData: array(item => fields(item, { mnemonic: identifier, ...counts('actionsExecuted firstStartedMs lastEndedMs actionsCreated'), systemTime: duration, userTime: duration })),
      runnerCount: array(item => fields(item, { name: identifier, execKind: identifier, count: numeric })),
      actionCacheStatistics: item => fields(item, {
        ...counts('sizeInBytes saveTimeInMs hits misses loadTimeInMs cacheCheckSemaphoreWaitTimeInMs'),
        missDetails: array(detail => fields(detail, { count: numeric, reason: identifier })),
      }),
    }),
    timingMetrics: entry => fields(entry, { ...counts('cpuTimeInMs wallTimeInMs analysisPhaseTimeInMs executionPhaseTimeInMs actionsExecutionStartInMs'), criticalPathTime: duration }),
    memoryMetrics: entry => fields(entry, {
      ...counts('usedHeapSizePostBuild peakPostGcHeapSize peakPostGcTenuredSpaceHeapSize'),
      garbageMetrics: array(item => fields(item, { type: identifier, garbageCollected: numeric })),
    }),
    targetMetrics: entry => fields(entry, counts('targetsLoaded targetsConfigured targetsConfiguredNotIncludingAspects')),
  });
}

/** Opaque IDs preserve BEP graph equality; no raw string labels/URLs are public. */
export function projectBep(text, context, { publicInput = false } = {}) {
  if (typeof text !== 'string' || !text.trim() || Buffer.byteLength(text) > MAX_BEP_BYTES) throw new Error('Missing or oversized BEP');
  const identifier = value => opaque(value, publicInput);
  const schemas = identifierSchemas(identifier);
  const output = [];
  let outputBytes = 0;
  for (const line of text.split(/\r?\n/)) {
    if (!line.trim()) continue;
    if (Buffer.byteLength(line) > 16 * 1024 * 1024 || output.length >= 500000) throw new Error('BEP event limit exceeded');
    let event;
    try { event = JSON.parse(line); } catch { throw new Error('Malformed BEP JSON'); }
    if (!object(event)) throw new Error('Invalid BEP event');
    const projected = { id: projectId(event.id, schemas) };
    if (event.children !== undefined) projected.children = array(id => projectId(id, schemas))(event.children);
    if (event.lastMessage !== undefined) projected.lastMessage = boolean(event.lastMessage);
    if (event.started !== undefined) {
      const started = fields(event.started, { uuid: value => value, buildToolVersion: value => value, command: value => value, startTime: timestamp, startTimeMillis: numeric });
      if (started.uuid !== context.invocationId || started.command !== context.command || started.buildToolVersion !== '9.0.0') throw new Error('BEP identity does not match approved context');
      projected.started = started;
    }
    if (event.buildMetadata !== undefined) {
      const source = event.buildMetadata?.metadata;
      if (!object(source)) throw new Error('Missing approved BEP metadata');
      const approved = {};
      for (const [key, field] of Object.entries(metadataFields)) {
        if (source[key] !== String(context[field])) throw new Error('BEP metadata does not match approved context');
        approved[key] = source[key];
      }
      projected.buildMetadata = { metadata: approved };
    }
    if (event.finished !== undefined) projected.finished = fields(event.finished, {
      exitCode: value => fields(value, { code: numeric }), finishTime: timestamp, finishTimeMillis: numeric,
    });
    if (event.action !== undefined) projected.action = fields(event.action, { success: boolean, exitCode: numeric, type: identifier, startTime: timestamp, endTime: timestamp });
    if (event.completed !== undefined) projected.completed = fields(event.completed, { success: boolean });
    if (event.aborted !== undefined) projected.aborted = fields(event.aborted, { reason: enumeration(abortReasons) });
    if (event.testResult !== undefined) projected.testResult = fields(event.testResult, {
      status: enumeration(testStatuses), cachedLocally: boolean, testAttemptDuration: duration,
      testAttemptDurationMillis: numeric, testAttemptStart: timestamp, testAttemptStartMillisEpoch: numeric,
      executionInfo: value => fields(value, { cachedRemotely: boolean, exitCode: numeric }),
    });
    if (event.testSummary !== undefined) projected.testSummary = fields(event.testSummary, {
      overallStatus: enumeration(testStatuses), ...counts('totalRunCount runCount attemptCount shardCount totalNumCached totalRunDurationMillis firstStartTimeMillis lastStopTimeMillis'),
      totalRunDuration: duration, firstStartTime: timestamp, lastStopTime: timestamp,
    });
    if (event.buildMetrics !== undefined) projected.buildMetrics = metrics(event.buildMetrics, identifier);
    // These oneof payloads are retained as empty messages for graph/proto shape;
    // their original command/env/text/URI content is never copied.
    for (const key of ['unstructuredCommandLine', 'structuredCommandLine', 'optionsParsed', 'progress', 'buildToolLogs', 'workspaceStatus', 'configuration', 'workspaceInfo', 'namedSetOfFiles', 'configured', 'expanded', 'fetch', 'targetSummary', 'convenienceSymlinksIdentified', 'execRequest']) {
      if (Object.hasOwn(event, key)) projected[key] = {};
    }
    const serialized = JSON.stringify(projected);
    outputBytes += Buffer.byteLength(serialized) + 1;
    if (outputBytes > MAX_BEP_BYTES) throw new Error('Projected BEP byte limit exceeded');
    output.push(serialized);
  }
  return `${output.join('\n')}\n`;
}

function projectProfileJson(bytes, { publicInput = false } = {}) {
  let profile;
  try {
    if (!isUtf8(bytes)) throw new Error('Invalid UTF-8');
    profile = JSON.parse(bytes.toString('utf8'));
  } catch { throw new Error('Malformed trace profile'); }
  if (!object(profile) || !Array.isArray(profile.traceEvents) || profile.traceEvents.length > 1000000) throw new Error('Invalid trace profile schema');
  const names = new Map();
  const counters = new Map();
  const traceEvents = [];
  for (const event of profile.traceEvents) {
    if (!object(event) || !['M', 'X', 'i', 'I', 'C', 'B', 'E', 's', 't', 'f'].includes(event.ph)) throw new Error('Unsupported trace event schema');
    if (event.ph === 'M') continue;
    if (typeof event.name !== 'string' || event.name.length > 16384) throw new Error('Invalid trace event name');
    if (!names.has(event.name)) names.set(event.name, names.size + 1);
    const clean = { name: `Bazel event ${names.get(event.name)}`, ph: event.ph };
    for (const key of ['ts', 'dur', 'pid', 'tid', 'tts', 'tdur']) if (event[key] !== undefined) {
      if (typeof event[key] !== 'number') throw new Error('Invalid trace numeric field');
      if (!Number.isFinite(event[key]) || Math.abs(event[key]) > Number.MAX_SAFE_INTEGER || (key !== 'ts' && event[key] < 0)) throw new Error('Invalid trace numeric field');
      clean[key] = event[key];
    }
    if (event.id !== undefined) {
      if (!['number', 'string'].includes(typeof event.id)) throw new Error('Invalid trace flow ID');
      if (typeof event.id === 'number' && !Number.isSafeInteger(event.id)) throw new Error('Invalid trace flow ID');
      clean.id = publicInput ? opaque(event.id, true) : opaque(JSON.stringify(event.id));
    }
    if (event.s !== undefined) clean.s = enumeration(['t', 'p', 'g'])(event.s);
    if (event.ph === 'C') {
      if (!object(event.args)) throw new Error('Invalid trace counter schema');
      const args = {};
      for (const [key, value] of Object.entries(event.args)) {
        if (typeof value !== 'number' || !Number.isFinite(value)) continue;
        if (Math.abs(value) > Number.MAX_SAFE_INTEGER) throw new Error('Invalid trace counter magnitude');
        if (!counters.has(key)) counters.set(key, counters.size + 1);
        args[`counter_${counters.get(key)}`] = value;
      }
      clean.args = args;
    }
    traceEvents.push(clean);
  }
  const output = Buffer.from(JSON.stringify({ traceEvents }));
  if (output.length > MAX_PROFILE_BYTES) throw new Error('Oversized public profile');
  return output;
}

/** Publish generic timing/counter tracks; arbitrary names, args and metadata drop. */
export function projectProfile(input, { publicInput = false } = {}) {
  if (!Buffer.isBuffer(input) || !input.length || input.length > (publicInput ? MAX_PROFILE_BYTES : MAX_COMPRESSED_PROFILE_BYTES)) throw new Error('Missing or oversized trace profile');
  let bytes = input;
  if (!publicInput) {
    try { bytes = gunzipSync(input, { maxOutputLength: MAX_PROFILE_BYTES }); }
    catch { throw new Error('Malformed or oversized trace profile'); }
  }
  return projectProfileJson(bytes, { publicInput });
}

/** Every published byte must belong to the exact canonical public JSON. */
export function validatePublicProfile(input) {
  if (!Buffer.isBuffer(input) || !input.length || input.length > MAX_PROFILE_BYTES) throw new Error('Invalid public profile size');
  if (!projectProfile(input, { publicInput: true }).equals(input)) throw new Error('Unprojected public profile fields');
  return input;
}
