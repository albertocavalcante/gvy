import { createHash } from 'node:crypto';

export function effectiveGradleJvmArgs(args, properties) {
  const supplied = args.filter(value => value.startsWith('-Dorg.gradle.jvmargs=')).at(-1);
  const configured = [...properties.matchAll(/^\s*org\.gradle\.jvmargs\s*=(.*)$/gm)].at(-1)?.[1];
  const value = supplied === undefined ? configured?.trim() : supplied.slice('-Dorg.gradle.jvmargs='.length);
  if (!value || value.length > 32768 || /[\x00-\x1f\x7f]/.test(value) || value.endsWith('\\')) {
    throw new Error('Memory observation requires explicit single-line Gradle JVM arguments');
  }
  if (/-XX:[+-]?ErrorFile/.test(value)) throw new Error('Memory observation requires its private ErrorFile ownership option');
  return value;
}

export function gradleMemoryPlan({ context, producer, job, jvmArgs, privateDirectory }) {
  if (!/^\/[A-Za-z0-9._/-]{1,4095}$/.test(privateDirectory ?? '')) {
    throw new Error('Memory observation requires an absolute private directory with simple path characters');
  }
  if (!/^[A-Za-z_][A-Za-z0-9_-]{0,99}$/.test(job ?? '')) throw new Error('Memory observation requires a valid GitHub job identity');
  if (!/^[a-z0-9][a-z0-9-]{0,79}$/.test(producer ?? '')) throw new Error('Memory observation requires a valid producer');
  if (![context?.headSha, context?.testedSha].every(value => /^[a-f0-9]{40}$/.test(value ?? '')) ||
      !/^[A-Za-z0-9_.:-]{1,200}$/.test(context?.runId ?? '') || !Number.isSafeInteger(context?.runAttempt) || context.runAttempt < 1 ||
      !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(context?.invocationId ?? '')) {
    throw new Error('Memory observation requires complete invocation identity');
  }
  const identity = { headSha: context.headSha, testedSha: context.testedSha, runId: context.runId,
    runAttempt: context.runAttempt, producer, invocationId: context.invocationId };
  // Keep one daemon reusable across this job's producers, while distinguishing
  // other runs, attempts, checkouts and jobs. No private arguments enter identity.
  const ownerMarker = createHash('sha256').update(JSON.stringify([
    context.headSha, context.testedSha, context.runId, context.runAttempt, job,
  ])).digest('hex');
  // Custom -D properties are mutable and filtered from Gradle daemon arguments.
  // ErrorFile survives the pinned launcher's immutable extra-argument path. It
  // changes crash-file routing only; the file is outside every artifact root.
  const ownerArgument = '-XX:ErrorFile=' + privateDirectory + '/gvy-memory-' + ownerMarker + '-hs_err_pid%p.log';
  return { identity, ownerArgument, jvmArgs: jvmArgs + ' ' + ownerArgument };
}
