import { LanguageClient } from 'vscode-languageclient/node';
import { RequestType } from 'vscode-languageserver-protocol';

export interface Test {
    test: string;
    line: number;
}

export interface TestSuite {
    uri: string;
    suite: string;
    tests: Test[];
}

const DiscoverTestsRequest = new RequestType<
    { workspaceUri: string },
    TestSuite[] | null,
    void
>('groovy/discoverTests');

export class TestService {
    constructor(private readonly client: LanguageClient) {}

    async discoverTestsInWorkspace(workspaceUri: string): Promise<TestSuite[]> {
        const suites = await this.client.sendRequest(DiscoverTestsRequest, { workspaceUri });
        return suites || [];
    }
}
