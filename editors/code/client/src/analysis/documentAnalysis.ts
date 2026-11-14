// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

export interface DocumentAnalysis {
  uri: string;
  sessionId: string;
  version: number;
  runId: number;
  compiler: {
    complete: boolean;
    phase?: string | null;
    reason?: string | null;
    sourceMembershipKnown?: boolean | null;
    sourceVisibilityKnown?: boolean | null;
    sourceModelMessage?: string | null;
  };
  providerFailures: Array<{
    provider: string;
    message: string;
    configurationUri?: string | null;
  }>;
}

/** Tracks open snapshots and run watermarks within their document sessions. */
export class AnalysisStore {
  private readonly versions = new Map<string, number>();
  private readonly results = new Map<string, DocumentAnalysis>();
  private readonly sessions = new Map<string, string>();
  private readonly runs = new Map<string, number>();

  reset(): void {
    this.versions.clear();
    this.results.clear();
    this.runs.clear();
    this.sessions.clear();
  }
  open(uri: string, version: number): void {
    this.versions.set(uri, version);
    this.sessions.delete(uri);
    this.results.delete(uri);
    this.runs.delete(uri);
  }
  change(uri: string, version: number): void {
    if (this.versions.get(uri) === version) return;
    this.versions.set(uri, version);
    this.results.delete(uri);
  }
  close(uri: string): void {
    this.versions.delete(uri);
    this.sessions.delete(uri);
    this.results.delete(uri);
    this.runs.delete(uri);
  }
  session(uri: string, sessionId: string): void {
    if (!this.versions.has(uri)) return;
    if (this.sessions.get(uri) !== sessionId) {
      this.runs.delete(uri);
      this.results.delete(uri);
    }
    this.sessions.set(uri, sessionId);
  }
  accept(result: DocumentAnalysis): boolean {
    if (
      this.versions.get(result.uri) !== result.version ||
      !result.sessionId ||
      this.sessions.get(result.uri) !== result.sessionId ||
      result.runId <= (this.runs.get(result.uri) ?? -1)
    )
      return false;
    this.runs.set(result.uri, result.runId);
    this.results.set(result.uri, result);
    return true;
  }
  get(uri: string): DocumentAnalysis | undefined {
    return this.results.get(uri);
  }
}

export function analysisLimitations(result: DocumentAnalysis): string[] {
  return [
    !result.compiler.complete ? "Type analysis is limited" : "",
    result.providerFailures.length ? "Linting failed" : "",
    result.compiler.sourceMembershipKnown === false
      ? "Source membership is not yet known"
      : "",
    result.compiler.sourceMembershipKnown !== false &&
    result.compiler.sourceVisibilityKnown === false
      ? "Project source lookup is inferred"
      : "",
  ].filter(Boolean);
}
