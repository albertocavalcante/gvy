import {
    DocumentFormattingEditProvider,
    TextDocument,
    FormattingOptions,
    CancellationToken,
    TextEdit,
    ProviderResult
} from 'vscode';
import { LanguageClient } from 'vscode-languageclient/node';
import { GroovyFormatter } from './GroovyFormatter';

export class GroovyFormattingProvider implements DocumentFormattingEditProvider {
    private readonly formatter: GroovyFormatter;

    constructor(client: LanguageClient) {
        this.formatter = new GroovyFormatter(client);
    }

    provideDocumentFormattingEdits(document: TextDocument, options: FormattingOptions, token: CancellationToken): ProviderResult<TextEdit[]> {
        if (token.isCancellationRequested) {
            return [];
        }
        return this.formatter.formatDocument(document.uri.toString(), options) as Promise<TextEdit[]>;
    }
}
