import { expect } from 'chai';
import * as sinon from 'sinon';
import { GradleService, GradleTask } from '../../../../src/features/gradle/GradleService';
import { RequestType } from 'vscode-languageserver-protocol';

describe('GradleService', () => {
    let sandbox: sinon.SinonSandbox;
    let gradleService: GradleService;
    let mockLanguageClient: any;

    beforeEach(() => {
        sandbox = sinon.createSandbox();
        mockLanguageClient = {
            sendRequest: sandbox.stub()
        };
        gradleService = new GradleService(mockLanguageClient);
    });

    afterEach(() => {
        sandbox.restore();
    });

    it('should send a gradleTasks request to the LSP', async () => {
        const workspaceUri = 'file:///path/to/workspace';
        const expectedTasks: GradleTask[] = [
            { name: 'build', group: 'build', project: 'root' },
            { name: 'test', group: 'verification', project: 'root' }
        ];
        
        mockLanguageClient.sendRequest.resolves(expectedTasks);

        const tasks = await gradleService.getTasks(workspaceUri);

        expect(mockLanguageClient.sendRequest.calledOnce).to.be.true;
        const args = mockLanguageClient.sendRequest.firstCall.args;
        
        // Check request type/method name
        const requestType = args[0] as RequestType<any, any, any>;
        expect(requestType.method).to.equal('groovy/gradleTasks');
        
        // Check request parameters
        expect(args[1]).to.deep.equal({ workspaceUri });

        // Check result
        expect(tasks).to.deep.equal(expectedTasks);
    });

    it('should handle errors from the LSP', async () => {
        const workspaceUri = 'file:///path/to/workspace';
        const error = new Error('LSP Gradle Error');
        mockLanguageClient.sendRequest.rejects(error);

        try {
            await gradleService.getTasks(workspaceUri);
            expect.fail('Should have thrown an error');
        } catch (e: any) {
            expect(e.message).to.equal('LSP Gradle Error');
        }
    });

    it('should return an empty array if LSP returns null', async () => {
        const workspaceUri = 'file:///path/to/workspace';
        mockLanguageClient.sendRequest.resolves(null);

        const tasks = await gradleService.getTasks(workspaceUri);

        expect(tasks).to.be.an('array').that.is.empty;
    });
});
