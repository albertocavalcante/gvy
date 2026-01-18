import * as assert from "assert";
import * as sinon from "sinon";
import * as proxyquire from "proxyquire";

describe("ToolRegistry", () => {
  let sandbox: sinon.SinonSandbox;
  let mockVscode: unknown;
  let mockConfig: unknown;
  let getConfigurationStub: sinon.SinonStub;
  let ToolRegistryModule: unknown;

  beforeEach(() => {
    sandbox = sinon.createSandbox();

    // Mock Configuration
    mockConfig = {
      get: sandbox.stub(),
    };

    // Mock vscode.workspace.getConfiguration
    getConfigurationStub = sandbox.stub().returns(mockConfig);

    mockVscode = {
      workspace: {
        getConfiguration: getConfigurationStub,
      },
    };

    // Load module with mocked dependencies
    ToolRegistryModule = proxyquire.noCallThru()(
      "../../../../src/features/ai/ToolRegistry",
      {
        vscode: mockVscode,
      },
    );
  });

  afterEach(() => {
    sandbox.restore();
  });

  describe("isToolEnabled", () => {
    it("should return false for any tool if master switch is disabled", () => {
      mockConfig.get.withArgs("ai.tools.enabled", false).returns(false);
      mockConfig.get.withArgs("ai.tools.allowed", ["all"]).returns(["all"]);

      const registry = new ToolRegistryModule.ToolRegistry();
      assert.strictEqual(registry.isToolEnabled("groovy_find_symbol"), false);
    });

    it('should return true if master switch is enabled and allowed list contains "all"', () => {
      mockConfig.get.withArgs("ai.tools.enabled", false).returns(true);
      mockConfig.get.withArgs("ai.tools.allowed", ["all"]).returns(["all"]);

      const registry = new ToolRegistryModule.ToolRegistry();
      assert.strictEqual(registry.isToolEnabled("groovy_find_symbol"), true);
    });

    it("should return true if tool is explicitly allowed", () => {
      mockConfig.get.withArgs("ai.tools.enabled", false).returns(true);
      mockConfig.get
        .withArgs("ai.tools.allowed", ["all"])
        .returns(["groovy_find_symbol"]);

      const registry = new ToolRegistryModule.ToolRegistry();
      assert.strictEqual(registry.isToolEnabled("groovy_find_symbol"), true);
    });

    it("should return false if tool is not in allowed list", () => {
      mockConfig.get.withArgs("ai.tools.enabled", false).returns(true);
      mockConfig.get
        .withArgs("ai.tools.allowed", ["all"])
        .returns(["groovy_other_tool"]);

      const registry = new ToolRegistryModule.ToolRegistry();
      assert.strictEqual(registry.isToolEnabled("groovy_find_symbol"), false);
    });

    it("should fetch fresh configuration on each isToolEnabled call", () => {
      const registry = new ToolRegistryModule.ToolRegistry();

      // First call: master switch disabled
      const mockConfig1 = {
        get: sandbox.stub(),
      };
      mockConfig1.get.withArgs("ai.tools.enabled", false).returns(false);
      mockConfig1.get.withArgs("ai.tools.allowed", ["all"]).returns(["all"]);
      getConfigurationStub.returns(mockConfig1);

      assert.strictEqual(
        registry.isToolEnabled("groovy_find_symbol"),
        false,
        "First call should return false",
      );
      assert.strictEqual(
        getConfigurationStub.callCount,
        1,
        "getConfiguration should be called once",
      );

      // Second call: master switch enabled with new config object
      const mockConfig2 = {
        get: sandbox.stub(),
      };
      mockConfig2.get.withArgs("ai.tools.enabled", false).returns(true);
      mockConfig2.get.withArgs("ai.tools.allowed", ["all"]).returns(["all"]);
      getConfigurationStub.returns(mockConfig2);

      assert.strictEqual(
        registry.isToolEnabled("groovy_find_symbol"),
        true,
        "Second call should return true with updated config",
      );
      assert.strictEqual(
        getConfigurationStub.callCount,
        2,
        "getConfiguration should be called again",
      );
    });

    it("should reflect runtime config changes between calls", () => {
      const registry = new ToolRegistryModule.ToolRegistry();

      // Scenario 1: Initially, tools are disabled
      const mockConfig1 = {
        get: sandbox.stub(),
      };
      mockConfig1.get.withArgs("ai.tools.enabled", false).returns(false);
      mockConfig1.get.withArgs("ai.tools.allowed", ["all"]).returns(["all"]);
      getConfigurationStub.returns(mockConfig1);

      const result1 = registry.isToolEnabled("groovy_find_symbol");
      assert.strictEqual(
        result1,
        false,
        "Tool should be disabled when master switch is off",
      );

      // Scenario 2: Config changes (user enables in settings)
      const mockConfig2 = {
        get: sandbox.stub(),
      };
      mockConfig2.get.withArgs("ai.tools.enabled", false).returns(true);
      mockConfig2.get
        .withArgs("ai.tools.allowed", ["all"])
        .returns(["groovy_find_symbol"]);
      getConfigurationStub.returns(mockConfig2);

      const result2 = registry.isToolEnabled("groovy_find_symbol");
      assert.strictEqual(
        result2,
        true,
        "Tool should be enabled after config change",
      );

      // Scenario 3: Config changes again (tool removed from allowed list)
      const mockConfig3 = {
        get: sandbox.stub(),
      };
      mockConfig3.get.withArgs("ai.tools.enabled", false).returns(true);
      mockConfig3.get
        .withArgs("ai.tools.allowed", ["all"])
        .returns(["other_tool"]);
      getConfigurationStub.returns(mockConfig3);

      const result3 = registry.isToolEnabled("groovy_find_symbol");
      assert.strictEqual(
        result3,
        false,
        "Tool should be disabled when removed from allowed list",
      );
    });

    it("should return false when allowed list is undefined", () => {
      mockConfig.get.withArgs("ai.tools.enabled", false).returns(true);
      mockConfig.get.withArgs("ai.tools.allowed", ["all"]).returns(undefined);

      const registry = new ToolRegistryModule.ToolRegistry();
      assert.strictEqual(
        registry.isToolEnabled("groovy_find_symbol"),
        false,
        "Tool should be disabled when allowed list is undefined",
      );
    });

    it("should return false when allowed list is null", () => {
      mockConfig.get.withArgs("ai.tools.enabled", false).returns(true);
      mockConfig.get.withArgs("ai.tools.allowed", ["all"]).returns(null);

      const registry = new ToolRegistryModule.ToolRegistry();
      assert.strictEqual(
        registry.isToolEnabled("groovy_find_symbol"),
        false,
        "Tool should be disabled when allowed list is null",
      );
    });
  });
});
