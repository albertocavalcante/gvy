package com.github.albertocavalcante.testing.jenkins

/**
 * Consolidated Jenkins test stubs.
 *
 * This file provides stub classes and annotations that mimic Jenkins
 * APIs for testing purposes without requiring the full Jenkins runtime.
 * These stubs allow tests to compile against Jenkins-style code without
 * pulling in heavyweight Jenkins dependencies.
 *
 * These stubs are minimal and only include the structure needed for
 * testing purposes. They do not implement full Jenkins functionality.
 */

// ========== hudson.model package ==========

/**
 * Stub for Hudson Descriptor class.
 *
 * In Jenkins, Descriptor is used to describe the configuration
 * of a particular type of object (e.g., build step, publisher).
 */
open class Descriptor<T>

// ========== org.jenkinsci packages ==========

/**
 * Stub for Jenkins Symbol annotation.
 *
 * The Symbol annotation is used to provide short names for Jenkins
 * extension points that can be used in Pipeline DSL.
 */
annotation class Symbol(val value: Array<String>)

/**
 * Stub for Jenkins GlobalVariable class.
 *
 * GlobalVariable represents a global variable in the Jenkins Pipeline DSL
 * (e.g., env, currentBuild, params).
 */
open class GlobalVariable {
    open fun getName(): String? = null
}

/**
 * Stub for Jenkins Step class.
 *
 * Step represents a Pipeline step that can be executed.
 */
open class Step

/**
 * Stub for Jenkins StepDescriptor class.
 *
 * StepDescriptor provides metadata about a Pipeline step,
 * including its function name for DSL usage.
 */
open class StepDescriptor {
    open fun getFunctionName(): String? = null
}

// ========== org.kohsuke.stapler packages ==========

/**
 * Stub for Stapler DataBoundConstructor annotation.
 *
 * DataBoundConstructor marks a constructor that should be used
 * for binding HTTP form data to an object.
 */
annotation class DataBoundConstructor

/**
 * Stub for Stapler DataBoundSetter annotation.
 *
 * DataBoundSetter marks a setter method that should be used
 * for binding HTTP form data to an object property.
 */
annotation class DataBoundSetter
