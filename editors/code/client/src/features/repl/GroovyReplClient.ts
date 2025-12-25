import { LanguageClient } from 'vscode-languageclient/node';

export interface ReplResult {
    result: string;
    output: string;
}

export class GroovyReplClient {
    constructor(private readonly client: LanguageClient) {}

    async evaluate(expression: string): Promise<ReplResult> {
        return this.client.sendRequest('groovy/execute', { expression });
    }
}
