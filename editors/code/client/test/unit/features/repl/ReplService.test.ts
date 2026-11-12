// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import * as sinon from "sinon";
import proxyquire from "proxyquire";
import { ErrorCodes, ResponseError } from "vscode-languageserver-protocol";

class FakeEmitter<T> {
  private listeners: Array<(e: T) => void> = [];
  event = (listener: (e: T) => void) => {
    this.listeners.push(listener);
    return { dispose: () => {} };
  };
  fire(e: T) {
    this.listeners.forEach((l) => l(e));
  }
  dispose() {}
}

interface Pty {
  onDidWrite: (l: (s: string) => void) => unknown;
  open: () => void;
  close: () => void;
}

describe("ReplService", () => {
  let terminals: Array<{
    pty: Pty;
    dispose: sinon.SinonStub;
    written: string[];
  }>;
  let commands: Map<string, () => Promise<void>>;
  let sendRequest: sinon.SinonStub;
  let service: { initialize: (ctx: unknown) => void };
  let showWarningMessage: sinon.SinonStub;

  beforeEach(() => {
    terminals = [];
    commands = new Map();
    sendRequest = sinon.stub().resolves({ result: "2", output: "" });
    showWarningMessage = sinon.stub();
    const vscodeMock = {
      EventEmitter: FakeEmitter,
      window: {
        activeTextEditor: {
          document: {
            getText: () => "1 + 1",
            lineAt: () => ({ text: "1 + 1" }),
          },
          selection: { active: { line: 0 } },
        },
        createTerminal: ({ pty }: { pty: Pty }) => {
          const t = {
            pty,
            dispose: sinon.stub(),
            written: [] as string[],
            show: () => {},
          };
          // VS Code ignores writes fired before Pseudoterminal.open.
          let opened = false;
          const open = pty.open.bind(pty);
          pty.open = () => {
            opened = true;
            open();
          };
          pty.onDidWrite((s) => opened && t.written.push(s));
          terminals.push(t);
          return t;
        },
        showErrorMessage: sinon.stub(),
        showInformationMessage: sinon.stub(),
        showWarningMessage,
      },
      commands: {
        registerCommand: (id: string, cb: () => Promise<void>) => {
          commands.set(id, cb);
          return { dispose: () => {} };
        },
      },
    };
    const mod = proxyquire.noCallThru()("../../../../src/features/repl/index", {
      vscode: vscodeMock,
      "../../server/client": { getClient: () => ({ sendRequest }) },
      "./ReplStatusBar": {
        ReplStatusBar: class {
          update() {}
          dispose() {}
        },
      },
    });
    service = new mod.ReplService();
    service.initialize({ subscriptions: [] });
  });

  it("keeps output written before the terminal opened", async () => {
    await commands.get("groovy.repl.sendLine")!();
    const [terminal] = terminals;
    terminal.pty.open();
    const text = terminal.written.join("");
    assert.match(text, /> 1 \+ 1/);
    assert.match(text, /<= 2/);
  });

  it("does not let the old terminal's close event kill the restarted one", async () => {
    await commands.get("groovy.repl.start")!();
    await commands.get("groovy.repl.restart")!();
    assert.strictEqual(terminals.length, 2);
    const [old, current] = terminals;

    old.pty.close(); // VS Code reports the disposed terminal asynchronously
    assert.ok(current.dispose.notCalled, "restarted terminal must survive");

    await commands.get("groovy.repl.sendLine")!();
    assert.strictEqual(terminals.length, 2, "no third terminal");
  });

  it("says the REPL is unsupported when the server lacks groovy/execute", async () => {
    sendRequest.rejects(
      new ResponseError(
        ErrorCodes.MethodNotFound,
        "Unsupported request method: groovy/execute",
      ),
    );
    await commands.get("groovy.repl.sendLine")!();
    const [terminal] = terminals;
    terminal.pty.open();
    const text = terminal.written.join("");
    assert.match(text, /not supported by this server version/);
    assert.doesNotMatch(text, /Unsupported request method/);
    assert.ok(
      showWarningMessage.calledOnce,
      "user is told outside the terminal",
    );
  });

  it("shows the unsupported popup once, then only writes to the terminal", async () => {
    sendRequest.rejects(
      new ResponseError(
        ErrorCodes.MethodNotFound,
        "Unsupported request method: groovy/execute",
      ),
    );
    await commands.get("groovy.repl.sendLine")!();
    await commands.get("groovy.repl.sendLine")!();
    const [terminal] = terminals;
    terminal.pty.open();
    const text = terminal.written.join("");
    assert.strictEqual(
      text.match(/not supported by this server version/g)?.length,
      2,
    );
    assert.ok(showWarningMessage.calledOnce, "popup repeated");
  });
});
