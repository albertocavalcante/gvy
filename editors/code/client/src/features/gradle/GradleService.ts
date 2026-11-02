import { ClientProvider, requireClient } from "../../server/clientProvider";
import { RequestType } from "vscode-languageserver-protocol";

export interface GradleTask {
  name: string;
  group: string;
  project: string;
}

const GradleTasksRequest = new RequestType<
  { workspaceUri: string },
  GradleTask[] | null,
  void
>("groovy/gradleTasks");

export class GradleService {
  constructor(private readonly getClient: ClientProvider) {}

  private get client() {
    return requireClient(this.getClient);
  }

  async getTasks(workspaceUri: string): Promise<GradleTask[]> {
    const tasks = await this.client.sendRequest(GradleTasksRequest, {
      workspaceUri,
    });
    return tasks || [];
  }
}
