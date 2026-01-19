package com.cloudbees.groovy.cps;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-only stub for Jenkins pipeline CodeNarc rules.
 *
 * Some CodeNarc rules run at semantic analysis phase and require this annotation to be resolvable on the classpath.
 * We provide a minimal stub so our Jenkins ruleset integration tests can compile their sample code.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface NonCPS {}
