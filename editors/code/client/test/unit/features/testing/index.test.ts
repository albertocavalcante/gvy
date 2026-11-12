// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import * as sinon from "sinon";
import proxyquire from "proxyquire";

describe("testing feature registration", () => {
  it("refreshes after every successful start and releases the listener on disposal", () => {
    const refreshTests = sinon.stub();
    const listeners = new Set<() => void>();
    const context = { subscriptions: [] as Array<{ dispose: () => void }> };
    const { registerTestingFeatures } = proxyquire.noCallThru()(
      "../../../../src/features/testing",
      {
        vscode: {
          languages: {
            registerCodeLensProvider: () => ({ dispose() {} }),
          },
        },
        "../../server/client": {
          getClient: () => undefined,
          onClientStarted: (listener: () => void) => {
            listeners.add(listener);
            return { dispose: () => listeners.delete(listener) };
          },
        },
        "./GroovyTestController": {
          GroovyTestController: class {
            refreshTests = refreshTests;
          },
        },
        "./TestService": { TestService: class {} },
        "./LSPTestExecutionService": { LSPTestExecutionService: class {} },
        "./CoverageService": { CoverageService: class {} },
        "./TestCodeLensProvider": { TestCodeLensProvider: class {} },
      },
    );

    registerTestingFeatures(context, {});
    sinon.assert.notCalled(refreshTests);
    assert.strictEqual(listeners.size, 1);
    listeners.forEach((listener) => listener());
    sinon.assert.calledOnce(refreshTests);
    listeners.forEach((listener) => listener());
    sinon.assert.calledTwice(refreshTests);

    context.subscriptions.forEach((subscription) => subscription.dispose());
    assert.strictEqual(listeners.size, 0);
    listeners.forEach((listener) => listener());
    sinon.assert.calledTwice(refreshTests);
  });
});
