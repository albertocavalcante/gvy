// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

export interface ProcessPlan {
  executable: string;
  arguments: string[];
  workingDirectory: string;
  environment: Record<string, string>;
}
export type Plan =
  | { kind: "save"; scopeDirectory: string }
  | {
      kind: "unavailable";
      reason: string;
      reasonCode?: "invalidContext" | "invalidEntryPoint";
      alternativeCommand?: string;
    }
  | {
      kind: "choices";
      choiceType: "context" | "entryPoint";
      choices: { id: string; label: string }[];
    }
  | {
      kind: "preparation";
      contextLabel: string;
      token: string;
      process: ProcessPlan;
      scopeDirectory: string;
    }
  | {
      kind: "ready";
      contextLabel: string;
      runtimeLabel: string;
      compile?: ProcessPlan;
      launch?: ProcessPlan;
      outputDirectory: string;
      launchToken?: string;
    };
export interface ExecutionHost {
  trusted(): boolean;
  save(): Promise<boolean>;
  saveScope(directory: string): Promise<boolean>;
  prepare(token?: string, launchToken?: string): Promise<Plan>;
  clearInvalidSelection(
    code: "invalidContext" | "invalidEntryPoint",
  ): Promise<boolean>;
  choose(
    plan: Extract<Plan, { kind: "choices" }>,
  ): Promise<boolean | undefined>;
  run(process: ProcessPlan, label: string): Promise<boolean>;
  cancelled(): boolean;
  unavailable(reason: string, alternativeCommand?: string): void;
}

export async function executePlan(
  host: ExecutionHost,
): Promise<Extract<Plan, { kind: "ready" }> | undefined> {
  if (!host.trusted() || !(await host.save()) || host.cancelled()) return;
  let token: string | undefined;
  let savedScope: string | undefined;
  let selectionRetried = false;
  for (let attempts = 0; attempts < 20 && !host.cancelled(); attempts++) {
    const plan = await host.prepare(token);
    if (host.cancelled()) return;
    if (plan.kind === "unavailable") {
      if (
        plan.reasonCode &&
        !selectionRetried &&
        (await host.clearInvalidSelection(plan.reasonCode))
      ) {
        selectionRetried = true;
        savedScope = undefined;
        token = undefined;
        continue;
      }
      host.unavailable(plan.reason, plan.alternativeCommand);
      return;
    }
    if (plan.kind === "save") {
      if (savedScope === plan.scopeDirectory) {
        host.unavailable("Could not confirm the saved build scope.");
        return;
      }
      if (!(await host.saveScope(plan.scopeDirectory)) || host.cancelled())
        return;
      savedScope = plan.scopeDirectory;
      continue;
    }
    if (plan.kind === "choices") {
      if (!(await host.choose(plan))) return;
      savedScope = undefined;
      token = undefined;
      continue;
    }
    if (plan.kind === "preparation") {
      if (token) {
        host.unavailable(
          "Project preparation became stale. Run the file again.",
        );
        return;
      }
      if (savedScope !== plan.scopeDirectory) {
        if (!(await host.saveScope(plan.scopeDirectory)) || host.cancelled())
          return;
        savedScope = plan.scopeDirectory;
        continue;
      }
      if (
        !(await host.run(plan.process, `Build ${plan.contextLabel}`)) ||
        host.cancelled()
      )
        return;
      token = plan.token;
      continue;
    }
    if (
      plan.compile &&
      (!(await host.run(
        plan.compile,
        `Compile · ${plan.contextLabel} · ${plan.runtimeLabel}`,
      )) ||
        host.cancelled())
    )
      return;
    if (plan.compile || plan.launch) {
      if (!plan.launchToken) {
        host.unavailable(
          "The server did not supply a launch validation token.",
        );
        return;
      }
      const validated = await host.prepare(token, plan.launchToken);
      if (host.cancelled()) return;
      if (
        validated.kind !== "ready" ||
        (plan.launch && !validated.launch) ||
        validated.outputDirectory !== plan.outputDirectory
      ) {
        host.unavailable(
          validated.kind === "unavailable"
            ? validated.reason
            : "The execution plan changed. Run again.",
          validated.kind === "unavailable"
            ? validated.alternativeCommand
            : undefined,
        );
        return;
      }
      if (
        validated.launch &&
        (!(await host.run(
          validated.launch,
          `Run · ${validated.contextLabel} · ${validated.runtimeLabel}`,
        )) ||
          host.cancelled())
      )
        return;
    }
    return plan;
  }
  if (!host.cancelled())
    host.unavailable("Could not settle on an execution plan.");
}
