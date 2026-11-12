// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { expect } from "chai";
import * as sinon from "sinon";
import proxyquire from "proxyquire";
import { vscode } from "../../mocks/vscode";

// Import the type separately for static analysis
import type { ReplStatusBarLogic as ReplStatusBarLogicType } from "../../../../src/features/repl/ReplStatusBar";

type ProxyquireNoCallThru = {
  noCallThru: () => (
    path: string,
    stubs: unknown,
  ) => { ReplStatusBarLogic: typeof ReplStatusBarLogicType };
};

const { ReplStatusBarLogic } = (
  proxyquire as ProxyquireNoCallThru
).noCallThru()("../../../../src/features/repl/ReplStatusBar", {
  vscode: vscode,
});

describe("ReplStatusBarLogic", () => {
  let statusBarLogic: ReplStatusBarLogicType;

  beforeEach(() => {
    statusBarLogic = new ReplStatusBarLogic();
  });

  it("should return correct state when REPL is running", () => {
    const state = statusBarLogic.getState(true); // isRunning = true
    expect(state.text).to.equal("$(terminal) REPL");
    expect(state.tooltip).to.include("Groovy REPL is running");
    expect(state.command).to.equal("groovy.repl.show");
    expect(state.color?.id).to.equal("statusBarItem.activeBackground");
  });

  it("should return correct state when REPL is stopped", () => {
    const state = statusBarLogic.getState(false); // isRunning = false
    expect(state.text).to.equal("$(terminal) REPL");
    expect(state.tooltip).to.include("Groovy REPL is not running");
    expect(state.command).to.equal("groovy.repl.start");
    expect(state.color).to.be.undefined;
  });
});

describe("ReplStatusBar", () => {
  it("shows the status item only while a REPL session runs", () => {
    const item = { show: sinon.stub(), hide: sinon.stub() };
    const { ReplStatusBar } = proxyquire.noCallThru()(
      "../../../../src/features/repl/ReplStatusBar",
      {
        vscode: {
          ...vscode,
          window: { createStatusBarItem: () => item },
          StatusBarAlignment: { Right: 2 },
        },
      },
    );
    const bar = new ReplStatusBar();
    expect(item.show.called).to.equal(false);
    bar.update(true);
    expect(item.show.calledOnce).to.equal(true);
    bar.update(false);
    expect(item.hide.called).to.equal(true);
  });
});
