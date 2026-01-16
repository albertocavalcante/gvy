import { LanguageClient } from "vscode-languageclient/node";
import { RequestType } from "vscode-languageserver-protocol";

export interface DependencyInfo {
  name: string;
  version: string;
  scope: string;
  path: string;
  isTransitive: boolean;
}

export interface GetDependenciesParams {
  workspaceUri: string;
}

export interface DependenciesResult {
  dependencies: DependencyInfo[];
  buildTool: string;
}

const GetDependenciesRequest = new RequestType<
  GetDependenciesParams,
  DependenciesResult | null,
  void
>("groovy/workspace/dependencies");

export class DependencyService {
  constructor(private readonly client: LanguageClient) {}

  async getDependencies(
    workspaceUri: string,
  ): Promise<DependenciesResult | null> {
    return this.client.sendRequest(GetDependenciesRequest, { workspaceUri });
  }
}
