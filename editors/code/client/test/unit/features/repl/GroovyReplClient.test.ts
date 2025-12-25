import { expect } from 'chai';
import * as sinon from 'sinon';
import { GroovyReplClient } from '../../../../src/features/repl/GroovyReplClient';

describe('GroovyReplClient', () => {
    let sandbox: sinon.SinonSandbox;
    let replClient: GroovyReplClient;
    let mockLanguageClient: any;

    beforeEach(() => {
        sandbox = sinon.createSandbox();
        mockLanguageClient = {
            sendRequest: sandbox.stub()
        };
        replClient = new GroovyReplClient(mockLanguageClient);
    });

    afterEach(() => {
        sandbox.restore();
    });

    it('should send an evaluate request to the LSP', async () => {
        const expression = 'println "Hello World"';
        const expectedResult = { result: 'null', output: 'Hello World\n' };
        
        mockLanguageClient.sendRequest.resolves(expectedResult);

        const response = await replClient.evaluate(expression);

        expect(mockLanguageClient.sendRequest.calledOnce).to.be.true;
        const args = mockLanguageClient.sendRequest.firstCall.args;
        expect(args[0]).to.equal('groovy/execute');
        expect(args[1]).to.deep.equal({ expression });
        expect(response).to.deep.equal(expectedResult);
    });

    it('should handle errors during evaluation', async () => {
        const error = new Error('Compilation Error');
        mockLanguageClient.sendRequest.rejects(error);

        try {
            await replClient.evaluate('invalid code');
            expect.fail('Should have thrown an error');
        } catch (e: any) {
            expect(e.message).to.equal('Compilation Error');
        }
    });
});
