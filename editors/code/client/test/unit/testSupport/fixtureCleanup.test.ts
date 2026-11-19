// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { strict as assert } from "assert";
import { runWithFixtureCleanup } from "../../../src/test/support/fixtureCleanup";

describe("fixture cleanup", () => {
  it("runs teardown in order after a successful scenario", async () => {
    const actions: string[] = [];
    await runWithFixtureCleanup(async () => {
      actions.push("scenario");
    }, [
      async () => {
        actions.push("close");
      },
      async () => {
        actions.push("remove");
      },
    ]);
    assert.deepEqual(actions, ["scenario", "close", "remove"]);
  });

  it("removes the directory even if editor closure fails", async () => {
    const failure = new Error("editor did not close");
    let removed = false;
    await assert.rejects(
      runWithFixtureCleanup(async () => {}, [
        async () => {
          throw failure;
        },
        async () => {
          removed = true;
        },
      ]),
      (error) => error === failure,
    );
    assert.equal(removed, true);
  });

  it("preserves the original scenario failure after successful cleanup", async () => {
    const failure = new Error("completion assertion failed");
    let removed = false;
    await assert.rejects(
      runWithFixtureCleanup(async () => {
        throw failure;
      }, [
        async () => {
          removed = true;
        },
      ]),
      (error) => error === failure,
    );
    assert.equal(removed, true);
  });

  it("preserves every failure when the scenario, close and removal all fail", async () => {
    const failures = [
      new Error("assertion"),
      new Error("close"),
      new Error("EBUSY"),
    ];
    await assert.rejects(
      runWithFixtureCleanup(async () => {
        throw failures[0];
      }, [
        async () => {
          throw failures[1];
        },
        async () => {
          throw failures[2];
        },
      ]),
      (error) =>
        error instanceof AggregateError &&
        error.errors.length === failures.length &&
        error.errors.every((failure, index) => failure === failures[index]),
    );
  });

  it("keeps a persistent removal failure visible", async () => {
    const failure = new Error("directory remains locked");
    await assert.rejects(
      runWithFixtureCleanup(async () => {}, [
        async () => {
          throw failure;
        },
      ]),
      (error) => error === failure,
    );
  });

  it("does not discard an undefined rejection while attempting all cleanup", async () => {
    let removed = false;
    let rejected = false;
    try {
      await runWithFixtureCleanup(async () => {
        throw undefined;
      }, [
        async () => {
          removed = true;
        },
      ]);
    } catch (error) {
      rejected = true;
      assert.equal(error, undefined);
    }
    assert.equal(rejected, true);
    assert.equal(removed, true);
  });
});
