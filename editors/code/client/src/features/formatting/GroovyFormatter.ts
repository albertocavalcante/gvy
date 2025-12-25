import { LanguageClient } from 'vscode-languageclient/node';
import { DocumentFormattingRequest, TextEdit, FormattingOptions } from 'vscode-languageserver-protocol';

export class GroovyFormatter {
    constructor(private client: LanguageClient) {}

    async formatDocument(documentUri: string, options: FormattingOptions): Promise<TextEdit[] | null> {
        const params = {
            textDocument: { uri: documentUri },
            options: options
        };

        return this.client.sendRequest(DocumentFormattingRequest.type, params);
    }
}
