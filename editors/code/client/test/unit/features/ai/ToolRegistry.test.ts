import * as assert from 'assert';
import * as sinon from 'sinon';
import { ToolRegistry } from '../../../../src/features/ai/ToolRegistry';
import { ILSPToolService } from '../../../../src/features/ai/types';

describe('ToolRegistry', () => {
    let sandbox: sinon.SinonSandbox;
    let lspServiceStub: sinon.SinonStubbedInstance<ILSPToolService>;
    let mockConfig: any; // Mock WorkspaceConfiguration

    beforeEach(() => {
        sandbox = sinon.createSandbox();

        // Mock the LSP Service
        lspServiceStub = {
            findWorkspaceSymbol: sandbox.stub(),
            findReferences: sandbox.stub(),
            getDefinition: sandbox.stub()
        };

        // Mock Configuration
        mockConfig = {
            get: sandbox.stub()
        };
    });

    afterEach(() => {
        sandbox.restore();
    });

    it('should return false for any tool if master switch is disabled', () => {
        const registry = new ToolRegistry(mockConfig);

        mockConfig.get.withArgs('ai.tools.enabled').returns(false);
        mockConfig.get.withArgs('ai.tools.allowed').returns(['all']);

        assert.strictEqual(registry.isToolEnabled('groovy_find_symbol'), false);
    });

    it('should return true if master switch is enabled and allowed list contains "all"', () => {
        const registry = new ToolRegistry(mockConfig);

        mockConfig.get.withArgs('ai.tools.enabled').returns(true);
        mockConfig.get.withArgs('ai.tools.allowed').returns(['all']);

        assert.strictEqual(registry.isToolEnabled('groovy_find_symbol'), true);
    });

    it('should return true if tool is explicitly allowed', () => {
        const registry = new ToolRegistry(mockConfig);

        mockConfig.get.withArgs('ai.tools.enabled').returns(true);
        mockConfig.get.withArgs('ai.tools.allowed').returns(['groovy_find_symbol']);

        assert.strictEqual(registry.isToolEnabled('groovy_find_symbol'), true);
    });

    it('should return false if tool is not in allowed list', () => {
        const registry = new ToolRegistry(mockConfig);

        mockConfig.get.withArgs('ai.tools.enabled').returns(true);
        mockConfig.get.withArgs('ai.tools.allowed').returns(['groovy_other_tool']);

        assert.strictEqual(registry.isToolEnabled('groovy_find_symbol'), false);
    });
});
