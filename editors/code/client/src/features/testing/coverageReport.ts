// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { buildToolOf } from "./processInvocation";
import { TestCommand } from "./TestService";

/**
 * Coverage is two steps: the tests run first, then the report. The report is a
 * separate command so a project without the JaCoCo plugin cannot stop the
 * tests: both Gradle and Maven reject an unknown task or plugin prefix before
 * running anything.
 */

/** Shown once when the report step failed only because JaCoCo is not applied. */
export const COVERAGE_REPORT_UNAVAILABLE =
  "[COVERAGE] Coverage report unavailable: the jacoco plugin is not applied.";

/** The report command for the build tool that ran [testCommand], if it has one. */
export function coverageReportCommand(
  testCommand: TestCommand,
): TestCommand | undefined {
  const tool = buildToolOf(testCommand.executable);
  // `-x test`: the report task usually depends on `test`, and re-running it
  // without the test filter would redo the suite and replace this run's data.
  if (tool === "gradle")
    return { ...testCommand, args: ["jacocoTestReport", "-x", "test"] };
  if (tool === "maven") return { ...testCommand, args: ["jacoco:report"] };
  return undefined;
}

/** True when [output] says the report task or plugin does not exist at all. */
export function isJacocoPluginMissing(
  executable: string,
  output: string,
): boolean {
  const tool = buildToolOf(executable);
  if (tool === "gradle")
    return /Task 'jacocoTestReport' not found/u.test(output);
  if (tool === "maven")
    return /No plugin found for prefix 'jacoco'/u.test(output);
  return false;
}
