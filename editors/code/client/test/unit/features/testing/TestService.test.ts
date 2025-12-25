import { expect } from 'chai';
import * as sinon from 'sinon';
import { TestService, TestSuite } from '../../../../src/features/testing/TestService';
import { RequestType } from 'vscode-languageserver-protocol';

describe('TestService', () => {
    let sandbox: sinon.SinonSandbox;
    let testService: TestService;
    let mockLanguageClient: any;

    beforeEach(() => {
        sandbox = sinon.createSandbox();
        mockLanguageClient = {
            sendRequest: sandbox.stub()
        };
        testService = new TestService(mockLanguageClient);
    });

    afterEach(() => {
        sandbox.restore();
    });

    it('should send a discoverTests request to the LSP for a workspace', async () => {
        const workspaceUri = 'file:///path/to/workspace';
        const expectedSuites: TestSuite[] = [
            {
                uri: 'file:///path/to/workspace/MySpec.groovy',
                suite: 'MySpec',
                tests: [{ test: 'should do something', line: 5 }]
            }
        ];
        
        mockLanguageClient.sendRequest.resolves(expectedSuites);

        const suites = await testService.discoverTestsInWorkspace(workspaceUri);

        expect(mockLanguageClient.sendRequest.calledOnce).to.be.true;
        const args = mockLanguageClient.sendRequest.firstCall.args;
        
        const requestType = args[0] as RequestType<any, any, any>;
        expect(requestType.method).to.equal('groovy/discoverTests');
        
        expect(args[1]).to.deep.equal({ workspaceUri });

        expect(suites).to.deep.equal(expectedSuites);
    });

    it('should handle errors from the LSP during discovery', async () => {
        const workspaceUri = 'file:///path/to/workspace';
        mockLanguageClient.sendRequest.rejects(new Error('LSP Test Discovery Error'));

        try {
            await testService.discoverTestsInWorkspace(workspaceUri);
            expect.fail('Should have thrown an error');
        } catch (e: any) {
            expect(e.message).to.equal('LSP Test Discovery Error');
        }
    });

    it('should return an empty array if the LSP returns null during discovery', async () => {
        const workspaceUri = 'file:///path/to/workspace';
        mockLanguageClient.sendRequest.resolves(null);

        const suites = await testService.discoverTestsInWorkspace(workspaceUri);

        expect(suites).to.be.an('array').that.is.empty;
    });
});
