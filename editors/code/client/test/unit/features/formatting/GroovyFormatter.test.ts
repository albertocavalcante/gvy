import { expect } from 'chai';
import * as sinon from 'sinon';
import { DocumentFormattingRequest } from 'vscode-languageserver-protocol';
import { GroovyFormatter } from '../../../../src/features/formatting/GroovyFormatter';

describe('GroovyFormatter', () => {
    let sandbox: sinon.SinonSandbox;
    let formatter: GroovyFormatter;
    let mockClient: any;

    beforeEach(() => {
        sandbox = sinon.createSandbox();
        mockClient = {
            sendRequest: sandbox.stub()
        };
        formatter = new GroovyFormatter(mockClient);
    });

    afterEach(() => {
        sandbox.restore();
    });

    it('should send a formatting request to the LSP', async () => {
        const documentUri = 'file:///test.groovy';
        const options = { tabSize: 4, insertSpaces: true };
        
        const expectedEdits = [
            { range: { start: { line: 0, character: 0 }, end: { line: 0, character: 5 } }, newText: 'formatted' }
        ];

        mockClient.sendRequest.resolves(expectedEdits);

        const result = await formatter.formatDocument(documentUri, options);

        expect(mockClient.sendRequest.calledOnce).to.be.true;
        const args = mockClient.sendRequest.firstCall.args;
        expect(args[0]).to.equal(DocumentFormattingRequest.type);
        expect(args[1]).to.deep.equal({
            textDocument: { uri: documentUri },
            options: options
        });
        expect(result).to.deep.equal(expectedEdits);
    });

    it('should handle errors from the LSP', async () => {
        mockClient.sendRequest.rejects(new Error('LSP Error'));

        try {
            await formatter.formatDocument('file:///test.groovy', { tabSize: 4, insertSpaces: true });
            expect.fail('Should have thrown an error');
        } catch (error: any) {
            expect(error.message).to.equal('LSP Error');
        }
    });
});
