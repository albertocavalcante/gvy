import * as assert from 'assert';
import * as sinon from 'sinon';
import type * as vscode from 'vscode';
import { LSPToolService } from '../../../../src/features/ai/LSPToolService';
import { AISymbolInfo, AILocation } from '../../../../src/features/ai/types';

// Mock VS Code classes
class MockUri {
    static parse(value: string) { return new MockUri(value); }
    constructor(public readonly path: string) { }
    toString() { return this.path; }
}

class MockPosition {
    constructor(public readonly line: number, public readonly character: number) { }
}

class MockRange {
    constructor(
        public readonly start: MockPosition | { line: number, character: number },
        public readonly end: MockPosition | { line: number, character: number }
    ) { }
}

class MockLocation {
    constructor(public readonly uri: MockUri, public readonly range: MockRange) { }
}

const MockSymbolKind = {
    Class: 4,
    4: 'Class' // Reverse mapping manually since it's an object not a real enum in the mock
};

describe('LSPToolService', () => {
    let sandbox: sinon.SinonSandbox;
    let executeCommandStub: sinon.SinonStub;
    let getClientStub: sinon.SinonStub;
    let service: LSPToolService;
    let mockVscode: any;

    const mockUri = MockUri.parse('file:///test/project/MyClass.groovy');

    beforeEach(() => {
        sandbox = sinon.createSandbox();
        executeCommandStub = sandbox.stub();
        getClientStub = sandbox.stub();

        mockVscode = {
            Uri: MockUri,
            Position: MockPosition,
            Range: MockRange,
            Location: MockLocation,
            SymbolKind: MockSymbolKind,
            commands: {
                executeCommand: executeCommandStub
            }
        };

        service = new LSPToolService(
            mockVscode,
            getClientStub
        );
    });

    afterEach(() => {
        sandbox.restore();
    });

    describe('findWorkspaceSymbol', () => {
        it('should map symbols correctly', async () => {
            const mockSymbol = {
                name: 'MyClass',
                kind: 4, // Class
                containerName: 'com.example',
                location: new MockLocation(mockUri, new MockRange({ line: 0, character: 0 }, { line: 10, character: 0 }))
            };

            executeCommandStub.withArgs('vscode.executeWorkspaceSymbolProvider', 'MyClass')
                .resolves([mockSymbol]);

            const result: AISymbolInfo[] = await service.findWorkspaceSymbol('MyClass');

            assert.strictEqual(result.length, 1);
            assert.strictEqual(result[0].name, 'MyClass');
            assert.strictEqual(result[0].kind, 'Class');
            assert.strictEqual(result[0].location.uri, mockUri.toString());
        });
    });

    describe('findReferences', () => {
        it('should map locations correctly', async () => {
            const mockLocation = new MockLocation(mockUri, new MockRange({ line: 5, character: 0 }, { line: 5, character: 10 }));

            executeCommandStub.resolves([mockLocation]);

            const result: AILocation[] = await service.findReferences(mockUri.toString(), 5, 0);

            assert.strictEqual(result.length, 1);
            assert.strictEqual(result[0].uri, mockUri.toString());
            assert.strictEqual(result[0].range.start.line, 5);
        });
    });

    describe('getDefinition', () => {
        it('should handle single Location result', async () => {
            const mockLocation = new MockLocation(mockUri, new MockRange({ line: 20, character: 0 }, { line: 20, character: 10 }));
            executeCommandStub.resolves(mockLocation);

            const result = await service.getDefinition(mockUri.toString(), 10, 5);

            assert.ok(result);
            assert.strictEqual(result!.uri, mockUri.toString());
            assert.strictEqual(result!.range.start.line, 20);
        });

        it('should handle LocationLink[] result', async () => {
            const mockLink = {
                targetUri: mockUri,
                targetRange: new MockRange({ line: 20, character: 0 }, { line: 20, character: 10 }),
                targetSelectionRange: new MockRange({ line: 20, character: 0 }, { line: 20, character: 5 })
            };
            executeCommandStub.resolves([mockLink]);

            const result = await service.getDefinition(mockUri.toString(), 10, 5);

            assert.ok(result);
            assert.strictEqual(result!.uri, mockUri.toString());
            assert.strictEqual(result!.range.start.line, 20);
        });

        it('should return null if no definition found', async () => {
            executeCommandStub.resolves([]); // Empty array

            const result = await service.getDefinition(mockUri.toString(), 10, 5);
            assert.strictEqual(result, null);
        });
    });
});
