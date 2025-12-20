@file:Suppress("EmptyFunctionBlock", "UnusedParameter")

package com.github.albertocavalcante.groovyjenkins.scanning

import org.jenkinsci.Symbol
import org.jenkinsci.plugins.workflow.steps.Step
import org.jenkinsci.plugins.workflow.steps.StepDescriptor
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.DataBoundSetter

// Dummy step classes for testing Jenkins classpath scanning.
// These are intentionally minimal stubs - empty bodies and unused params are expected.

// 1. Standard Step with Method Setters
class MyTestStep @DataBoundConstructor constructor(val name: String) : Step() {
    @DataBoundSetter
    fun setOpt(@Suppress("UNUSED_PARAMETER") i: Int) = Unit

    @Symbol(["myTestStep"])
    class DescriptorImpl : StepDescriptor()
}

// 2. Step with Field Setters (Like MailStep)
class FieldStep @DataBoundConstructor constructor(val msg: String) : Step() {
    @DataBoundSetter
    var fieldParam: String = ""

    @DataBoundSetter
    var anotherField: Int = 0

    @Symbol(["fieldStep"])
    class DescriptorImpl : StepDescriptor()
}

// 3. Complex Step for existing test
class ComplexStep @DataBoundConstructor constructor(val requiredParam: String) : Step() {
    @DataBoundSetter
    fun setOpt(@Suppress("UNUSED_PARAMETER") opt: Int) = Unit

    @Symbol(["complexStep"])
    class DescriptorImpl : StepDescriptor()
}

// 4. Global Variable
@Symbol(["myGlobal"])
class MyGlobalVariable : org.jenkinsci.plugins.workflow.cps.GlobalVariable()
