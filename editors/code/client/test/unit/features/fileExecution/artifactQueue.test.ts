// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import { ArtifactQueue } from "../../../../src/features/fileExecution/artifactQueue";

describe("compile artifact publication", () => {
  it("serializes overlapping publications per file and removes only superseded output", async () => {
    const queue = new ArtifactQueue();
    let current = "old";
    const removed: string[] = [];
    let release!: () => void;
    let firstStarted!: () => void;
    const started = new Promise<void>((resolve) => {
      firstStarted = resolve;
    });
    const blocked = new Promise<void>((resolve) => {
      release = resolve;
    });
    const publish = (name: string, wait: boolean) =>
      queue.run("file", async () => {
        const previous = current;
        if (wait) {
          firstStarted();
          await blocked;
        }
        current = name;
        removed.push(previous);
      });
    const first = publish("first", true);
    await started;
    const second = publish("second", false);
    let otherFinished = false;
    await queue.run("another file", async () => {
      otherFinished = true;
    });
    assert.ok(otherFinished);
    assert.strictEqual(current, "old");
    release();
    await Promise.all([first, second]);
    assert.strictEqual(current, "second");
    assert.deepStrictEqual(removed, ["old", "first"]);
  });
  it("continues after a failed publication", async () => {
    const queue = new ArtifactQueue();
    await assert.rejects(
      queue.run("file", async () => {
        throw new Error("failed");
      }),
    );
    let ran = false;
    await queue.run("file", async () => {
      ran = true;
    });
    assert.ok(ran);
  });
});
