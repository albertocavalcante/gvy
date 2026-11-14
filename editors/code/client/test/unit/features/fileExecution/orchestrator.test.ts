// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import {
  executePlan,
  ExecutionHost,
  Plan,
} from "../../../../src/features/fileExecution/orchestrator";

describe("file execution orchestration", () => {
  const process = {
    executable: "java",
    arguments: ["space & unicode"],
    workingDirectory: "/work",
    environment: {},
  };
  function host(
    plans: Plan[],
    results: boolean[] = [],
  ): ExecutionHost & { calls: string[] } {
    const calls: string[] = [];
    return {
      calls,
      trusted: () => true,
      save: async () => {
        calls.push("save");
        return true;
      },
      saveScope: async () => {
        calls.push("scope");
        return true;
      },
      prepare: async (token, launchToken) => {
        calls.push(`prepare:${launchToken ?? token ?? ""}`);
        return plans.shift()!;
      },
      choose: async () => undefined,
      clearInvalidSelection: async () => false,
      run: async () => {
        calls.push("run");
        return results.shift() ?? true;
      },
      cancelled: () => false,
      unavailable: () => {},
    };
  }
  it("gates planning on trust and successful save", async () => {
    const h = host([]);
    h.trusted = () => false;
    await executePlan(h);
    assert.deepStrictEqual(h.calls, []);
    h.trusted = () => true;
    h.save = async () => false;
    await executePlan(h);
    assert.deepStrictEqual(h.calls, []);
  });
  it("saves project scope, builds, replans, then compiles and launches", async () => {
    const prep: Plan = {
      kind: "preparation",
      contextLabel: "project",
      token: "t",
      process,
      scopeDirectory: "/work",
    };
    const ready: Plan = {
      kind: "ready",
      contextLabel: "project",
      runtimeLabel: "Groovy",
      compile: process,
      launch: process,
      outputDirectory: "/out",
    };
    const h = host([
      prep,
      prep,
      { ...ready, launchToken: "launch" } as Plan,
      ready,
    ]);
    await executePlan(h);
    assert.deepStrictEqual(h.calls, [
      "save",
      "prepare:",
      "scope",
      "prepare:",
      "run",
      "prepare:t",
      "run",
      "prepare:launch",
      "run",
    ]);
  });
  it("never launches after build failure or cancellation", async () => {
    const prep: Plan = {
      kind: "preparation",
      contextLabel: "project",
      token: "t",
      process,
      scopeDirectory: "/work",
    };
    const h = host([prep, prep], [false]);
    await executePlan(h);
    assert.deepStrictEqual(h.calls, [
      "save",
      "prepare:",
      "scope",
      "prepare:",
      "run",
    ]);
    const c = host([prep, prep]);
    c.run = async () => {
      c.calls.push("run");
      c.cancelled = () => true;
      return true;
    };
    await executePlan(c);
    assert.deepStrictEqual(c.calls, [
      "save",
      "prepare:",
      "scope",
      "prepare:",
      "run",
    ]);
  });
  it("stops when scope save fails and when the refreshed plan is stale", async () => {
    const prep: Plan = {
      kind: "preparation",
      contextLabel: "project",
      token: "t",
      process,
      scopeDirectory: "/work",
    };
    const failedSave = host([prep]);
    failedSave.saveScope = async () => false;
    await executePlan(failedSave);
    assert.deepStrictEqual(failedSave.calls, ["save", "prepare:"]);
    const stale = host([
      prep,
      prep,
      { kind: "unavailable", reason: "changed" },
    ]);
    await executePlan(stale);
    assert.deepStrictEqual(stale.calls, [
      "save",
      "prepare:",
      "scope",
      "prepare:",
      "run",
      "prepare:t",
    ]);
  });
  it("does not return an artifact or launch when compilation fails", async () => {
    const ready: Plan = {
      kind: "ready",
      contextLabel: "standalone",
      runtimeLabel: "Groovy",
      compile: process,
      launch: process,
      outputDirectory: "/out",
    };
    const h = host([ready], [false]);
    assert.strictEqual(await executePlan(h), undefined);
    assert.deepStrictEqual(h.calls, ["save", "prepare:", "run"]);
  });
  it("revalidates after compile and never launches a stale plan", async () => {
    const ready = {
      kind: "ready",
      contextLabel: "project",
      runtimeLabel: "Groovy",
      compile: process,
      launch: process,
      launchToken: "launch",
      outputDirectory: "/out",
    } as Plan;
    const h = host([ready, { kind: "unavailable", reason: "changed" }]);
    assert.strictEqual(await executePlan(h), undefined);
    assert.deepStrictEqual(h.calls, [
      "save",
      "prepare:",
      "run",
      "prepare:launch",
    ]);
  });

  it("clears only an invalid remembered selection and retries once", async () => {
    const h = host([
      { kind: "unavailable", reason: "missing", reasonCode: "invalidContext" },
      {
        kind: "unavailable",
        reason: "still missing",
        reasonCode: "invalidContext",
      },
    ]);
    h.clearInvalidSelection = async (code) => {
      h.calls.push(`clear:${code}`);
      return true;
    };
    await executePlan(h);
    assert.deepStrictEqual(h.calls, [
      "save",
      "prepare:",
      "clear:invalidContext",
      "prepare:",
    ]);
  });
  it("does not publish compile output if inputs change during compilation", async () => {
    const ready: Plan = {
      kind: "ready",
      contextLabel: "standalone",
      runtimeLabel: "Groovy",
      compile: process,
      launchToken: "compile",
      outputDirectory: "/out",
    };
    const h = host([ready, { kind: "unavailable", reason: "changed" }]);
    assert.strictEqual(await executePlan(h), undefined);
    assert.deepStrictEqual(h.calls, [
      "save",
      "prepare:",
      "run",
      "prepare:compile",
    ]);
  });
  it("saves the build scope before project evaluation is allowed", async () => {
    const save: Plan = { kind: "save", scopeDirectory: "/work" };
    const ready: Plan = {
      kind: "ready",
      contextLabel: "standalone",
      runtimeLabel: "Groovy",
      outputDirectory: "/out",
    };
    const h = host([save, ready]);
    await executePlan(h);
    assert.deepStrictEqual(h.calls, ["save", "prepare:", "scope", "prepare:"]);
    const cancelled = host([save]);
    cancelled.saveScope = async () => false;
    await executePlan(cancelled);
    assert.deepStrictEqual(cancelled.calls, ["save", "prepare:"]);
  });
  it("saves the same scope again after selecting a context", async () => {
    const save: Plan = { kind: "save", scopeDirectory: "/work" };
    const choose: Plan = {
      kind: "choices",
      choiceType: "context",
      choices: [{ id: "project", label: "Project" }],
    };
    const ready: Plan = {
      kind: "ready",
      contextLabel: "project",
      runtimeLabel: "Groovy",
      outputDirectory: "/out",
    };
    const h = host([save, choose, save, ready]);
    h.choose = async () => {
      h.calls.push("choose");
      return true;
    };
    await executePlan(h);
    assert.deepStrictEqual(h.calls, [
      "save",
      "prepare:",
      "scope",
      "prepare:",
      "choose",
      "prepare:",
      "scope",
      "prepare:",
    ]);
  });
});
