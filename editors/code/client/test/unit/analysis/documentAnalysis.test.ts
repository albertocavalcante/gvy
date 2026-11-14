// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { assert } from "chai";
import {
  AnalysisStore,
  DocumentAnalysis,
  analysisLimitations,
} from "../../../src/analysis/documentAnalysis";

const result = (version: number, runId: number): DocumentAnalysis => ({
  uri: "file:///test.groovy",
  sessionId: "old",
  version,
  runId,
  compiler: {
    complete: false,
    phase: "SEMANTIC_ANALYSIS",
    reason: "Missing dependency",
  },
  providerFailures: [],
});

describe("document analysis snapshots", () => {
  it("accepts restarted server run numbers only for the new document session", () => {
    const store = new AnalysisStore();
    store.open("file:///test.groovy", 1);
    store.session("file:///test.groovy", "old");
    assert.isTrue(store.accept(result(1, 100)));
    store.open("file:///test.groovy", 1);
    store.session("file:///test.groovy", "new-server");
    assert.isFalse(store.accept(result(1, 101)));
    assert.isTrue(store.accept({ ...result(1, 1), sessionId: "new-server" }));
  });

  it("preserves analysis for document events with an unchanged version", () => {
    const store = new AnalysisStore();
    store.open("file:///test.groovy", 1);
    store.session("file:///test.groovy", "old");
    const snapshot = result(1, 1);
    assert.isTrue(store.accept(snapshot));
    store.change("file:///test.groovy", 1);
    assert.strictEqual(store.get("file:///test.groovy"), snapshot);
  });

  it("rejects outdated versions and runs, including after close and reopen", () => {
    const store = new AnalysisStore();
    store.open("file:///test.groovy", 1);
    store.session("file:///test.groovy", "old");
    assert.isTrue(store.accept(result(1, 2)));
    store.change("file:///test.groovy", 2);
    assert.isUndefined(store.get("file:///test.groovy"));
    assert.isFalse(store.accept(result(1, 3)));
    assert.isTrue(store.accept(result(2, 4)));
    assert.isFalse(store.accept(result(2, 3)));
    store.close("file:///test.groovy");
    store.open("file:///test.groovy", 1);
    store.session("file:///test.groovy", "new");
    assert.isFalse(store.accept(result(1, 5)));
    assert.isTrue(store.accept({ ...result(1, 6), sessionId: "new" }));
  });
});

describe("analysis limitations", () => {
  it("reports inferred dependency source lookup separately from compiler failure", () => {
    const snapshot = result(1, 1);
    snapshot.compiler.complete = true;
    snapshot.compiler.sourceMembershipKnown = true;
    snapshot.compiler.sourceVisibilityKnown = false;
    assert.deepEqual(analysisLimitations(snapshot), [
      "Project source lookup is inferred",
    ]);
    snapshot.compiler.sourceVisibilityKnown = true;
    assert.deepEqual(analysisLimitations(snapshot), []);
  });

  it("distinguishes compiler, provider and membership failures and clears on recovery", () => {
    const snapshot = result(1, 1);
    snapshot.providerFailures.push({
      provider: "CodeNarc",
      message: "Invalid ruleset",
    });
    snapshot.compiler.sourceMembershipKnown = false;
    assert.deepEqual(analysisLimitations(snapshot), [
      "Type analysis is limited",
      "Linting failed",
      "Source membership is not yet known",
    ]);
    snapshot.compiler.complete = true;
    snapshot.compiler.sourceMembershipKnown = true;
    snapshot.providerFailures = [];
    assert.deepEqual(analysisLimitations(snapshot), []);
  });
});
