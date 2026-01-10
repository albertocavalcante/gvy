package com.github.albertocavalcante.testing.jenkins

/**
 * Jenkins test stubs reference.
 *
 * Note: This file previously contained local Jenkins stub classes and annotations.
 *
 * Those stubs have been removed to avoid duplication with the canonical
 * test stubs defined in the groovy-jenkins module (under src/test/kotlin).
 * Please use the existing Jenkins stub types from their proper packages,
 * such as hudson.model, org.jenkinsci.*, and org.kohsuke.stapler.
 *
 * Available stubs in groovy-jenkins/src/test/kotlin/:
 * - hudson.model.Descriptor
 * - org.jenkinsci.Symbol
 * - org.jenkinsci.plugins.workflow.cps.GlobalVariable
 * - org.jenkinsci.plugins.workflow.steps.Step
 * - org.jenkinsci.plugins.workflow.steps.StepDescriptor
 * - org.kohsuke.stapler.DataBoundConstructor
 * - org.kohsuke.stapler.DataBoundSetter
 *
 * These stubs use the correct Jenkins package structure and can act as
 * drop-in replacements for the real Jenkins classes.
 */
@Deprecated(
    "This module previously contained duplicate Jenkins stubs. " +
        "Use the canonical stubs from groovy-jenkins/src/test/kotlin instead.",
    level = DeprecationLevel.WARNING,
)
object JenkinsStubs
