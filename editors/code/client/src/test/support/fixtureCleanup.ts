// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

type AsyncAction = () => Promise<void>;

/** Attempt every teardown step and retain both scenario and cleanup failures. */
export async function runWithFixtureCleanup(
  scenario: AsyncAction,
  cleanup: readonly AsyncAction[],
): Promise<void> {
  const failures: unknown[] = [];
  for (const action of [scenario, ...cleanup]) {
    try {
      await action();
    } catch (error) {
      failures.push(error);
    }
  }
  if (failures.length === 1) throw failures[0];
  if (failures.length > 1) {
    throw new AggregateError(
      failures,
      "Fixture execution and cleanup had multiple failures",
      { cause: failures[0] },
    );
  }
}
