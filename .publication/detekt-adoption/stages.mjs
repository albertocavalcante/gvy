// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
export const fixture = ':tools:lint:analysis-health';
export const stageNames = ['format', 'ordering', 'fixture', 'detekt-health', 'formatting-health'];
export const failureExit = value => Number.isInteger(value) && value > 0 && value < 256 ? value : 75;
export function remainingBudget(startWall, startMono, wall, mono, limit = 55 * 60_000) {
  return Math.min(startWall + limit - wall, startMono + limit - mono);
}
// Explicit fixed producer sequence; failure is terminal and later controls are not attempted.
export async function runStages(io) {
  const results = [];
  for (const name of stageNames) {
    await io.checkpoint(); const result = await io.stage(name); results.push({ name, ...result });
    await io.checkpoint();
    if (!result.ok) return { complete: false, ok: false, results, exitCode: failureExit(result.status), stoppedAfter: name };
  }
  return { complete: true, ok: true, results, exitCode: 0, productCommitAllowed: false };
}
export function commands(common, graph, output) {
  return {
    format: ['./gradlew', ...common, ':spotlessApply', fixture + ':spotlessApply'],
    ordering: ['./gradlew', ...common, '-I', graph, '-Pr6.graphMode=ordering', ':spotlessApply', fixture + ':lintFix', ':parser:lintFix', ':parser:core:lintFix', '--dry-run'],
    fixture: ['./gradlew', ...common, '-I', graph, '-Pr6.graphMode=fixture', ...['check', 'quality', 'build'].map(name => fixture + ':' + name)],
    'detekt-health': ['node', '--max-old-space-size=256', 'tools/lint/detekt-analysis-health.mjs', '--report-root', output + '/detekt-health', '--', ...common],
    'formatting-health': ['node', '--max-old-space-size=256', 'tools/lint/kotlin-formatting-health.mjs', '--report-root', output + '/formatting-health', '--', ...common],
  };
}
