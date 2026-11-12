import * as assert from "assert";
import * as sinon from "sinon";
import * as proxyquire from "proxyquire";
import type { ILSPToolService } from "../../../../src/features/ai/types";
import { TOOL_DEFINITIONS } from "../../../../src/features/ai/types";

type Invoke = (input: Record<string, unknown>) => Promise<unknown>;

for (const kind of ["CommandProvider", "LMToolProvider"] as const) {
  describe(`${kind} invocation policy`, () => {
    let enabled: unknown;
    let allowed: unknown;
    let handlers: Map<string, Invoke>;
    let registrations: sinon.SinonSpy[];
    let service: sinon.SinonStubbedInstance<ILSPToolService>;
    let provider: { dispose(): void };

    beforeEach(() => {
      enabled = false;
      allowed = ["all"];
      handlers = new Map();
      registrations = [];
      service = {
        findWorkspaceSymbol: sinon
          .stub<
            Parameters<ILSPToolService["findWorkspaceSymbol"]>,
            ReturnType<ILSPToolService["findWorkspaceSymbol"]>
          >()
          .resolves([]),
        findReferences: sinon
          .stub<
            Parameters<ILSPToolService["findReferences"]>,
            ReturnType<ILSPToolService["findReferences"]>
          >()
          .resolves([]),
        getDefinition: sinon
          .stub<
            Parameters<ILSPToolService["getDefinition"]>,
            ReturnType<ILSPToolService["getDefinition"]>
          >()
          .resolves(null),
      };
    });

    afterEach(() => provider?.dispose());

    function register(): void {
      const registerHandler = (name: string, invoke: Invoke) => {
        assert.strictEqual(handlers.has(name), false, "register only once");
        handlers.set(name, invoke);
        const dispose = sinon.spy();
        registrations.push(dispose);
        return { dispose };
      };
      const vscode = {
        workspace: {
          getConfiguration: () => ({
            get: (key: string) =>
              key === "ai.tools.enabled" ? enabled : allowed,
          }),
        },
        commands: { registerCommand: registerHandler },
        lm: {
          registerTool: (
            name: string,
            tool: {
              invoke(options: {
                input: Record<string, unknown>;
              }): Promise<unknown>;
            },
          ) => registerHandler(name, (input) => tool.invoke({ input })),
        },
        LanguageModelTextPart: class {
          constructor(readonly value: string) {}
        },
        LanguageModelToolResult: class {
          constructor(readonly content: unknown[]) {}
        },
      };
      const { ToolRegistry } = proxyquire.noCallThru()(
        "../../../../src/features/ai/ToolRegistry",
        { vscode },
      ) as typeof import("../../../../src/features/ai/ToolRegistry");
      const module = proxyquire.noCallThru()(
        `../../../../src/features/ai/${kind}`,
        { vscode },
      ) as Record<
        typeof kind,
        new (
          service: ILSPToolService,
          registry: InstanceType<typeof ToolRegistry>,
        ) => { dispose(): void }
      >;
      provider = new module[kind](service, new ToolRegistry());
    }

    function invoke(): Promise<unknown> {
      const name =
        kind === "CommandProvider"
          ? "groovy.ai.find_symbol"
          : "groovy_find_symbol";
      const handler = handlers.get(name);
      assert.ok(
        handler,
        "known handler must exist even when initially disabled",
      );
      return handler({ query: "Example" });
    }

    function assertNoLookup(): void {
      sinon.assert.notCalled(service.findWorkspaceSymbol);
      sinon.assert.notCalled(service.findReferences);
      sinon.assert.notCalled(service.getDefinition);
    }

    it("allows enabling after activation without re-registering", async () => {
      register();
      assert.strictEqual(handlers.size, TOOL_DEFINITIONS.length);
      await assert.rejects(invoke(), /disabled or not allowed/);
      assertNoLookup();
      enabled = true;
      await invoke();
      sinon.assert.calledOnceWithExactly(
        service.findWorkspaceSymbol,
        "Example",
      );
      assert.strictEqual(registrations.length, TOOL_DEFINITIONS.length);
    });

    it("revokes the same registered callback and permits re-enabling", async () => {
      enabled = true;
      register();
      enabled = false;
      await assert.rejects(invoke(), /disabled or not allowed/);
      assertNoLookup();
      enabled = true;
      await invoke();
      sinon.assert.calledOnce(service.findWorkspaceSymbol);
    });

    it("checks individual allowlist changes on every call", async () => {
      enabled = true;
      register();
      allowed = ["groovy_get_references"];
      await assert.rejects(invoke(), /disabled or not allowed/);
      assertNoLookup();
      allowed = ["groovy_find_symbol"];
      await invoke();
      sinon.assert.calledOnce(service.findWorkspaceSymbol);
      allowed = [];
      await assert.rejects(invoke(), /disabled or not allowed/);
      sinon.assert.calledOnce(service.findWorkspaceSymbol);
    });

    for (const invalid of ["all", 42, null, ["groovy_find_symbol", null]]) {
      it(`rejects malformed allowlist ${JSON.stringify(invalid)} before lookup`, async () => {
        enabled = true;
        register();
        allowed = invalid;
        await assert.rejects(invoke(), /disabled or not allowed/);
        assertNoLookup();
      });
    }

    it("rejects a truthy non-boolean master switch before lookup", async () => {
      enabled = true;
      register();
      enabled = "false";
      await assert.rejects(invoke(), /disabled or not allowed/);
      assertNoLookup();
    });

    it("disposes every registration", () => {
      register();
      provider.dispose();
      assert.strictEqual(registrations.length, TOOL_DEFINITIONS.length);
      registrations.forEach((dispose) => sinon.assert.calledOnce(dispose));
    });
  });
}
