package com.github.albertocavalcante.testing.jenkins

/**
 * Jenkins test stubs reference.
 *
 * Note: This file previously contained local Jenkins stub classes and annotations.
 *
 * Those stubs have been removed to avoid duplication. Jenkins stub classes
 * exist in groovy-jenkins/src/test/kotlin/ but are NOT currently exposed
 * as consumable test fixtures.
 *
 * **Current state**: Test sources from groovy-jenkins are NOT accessible
 * to other modules. To use Jenkins stubs in your tests:
 *
 * 1. Copy the needed stub classes locally to your test sources, OR
 * 2. Request that groovy-jenkins enables the java-test-fixtures plugin
 *    to expose these stubs as consumable test fixtures
 *
 * Example stub classes that exist (but are not consumable):
 * - hudson.model.Descriptor (in groovy-jenkins/src/test/kotlin)
 * - org.jenkinsci.Symbol
 * - org.jenkinsci.plugins.workflow.cps.GlobalVariable
 * - org.jenkinsci.plugins.workflow.steps.Step
 * - org.jenkinsci.plugins.workflow.steps.StepDescriptor
 * - org.kohsuke.stapler.DataBoundConstructor
 * - org.kohsuke.stapler.DataBoundSetter
 */
@Deprecated(
    "This module previously contained duplicate Jenkins stubs. " +
        "Jenkins stubs exist in groovy-jenkins/src/test/kotlin but are not exposed as test fixtures. " +
        "Copy needed stubs locally or request testFixtures support in groovy-jenkins.",
    level = DeprecationLevel.WARNING,
)
object JenkinsStubs
