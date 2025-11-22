package com.example

import groovy.transform.CompileStatic
import java.util.concurrent.ConcurrentHashMap

/**
 * A "Kitchen Sink" file designed to exercise the Groovy Parser.
 * It contains a wide variety of Groovy syntax features.
 */
@CompileStatic
class KitchenSink implements Serializable {
    // Properties with different modifiers
    private String secret = "secret"
    public final int constant = 42
    static String staticProp = "static"
    def dynamicProp = "dynamic"

    // Constructor
    KitchenSink(String initial) {
        this.secret = initial
        // Use the import to avoid unused import warning
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>()
        map.put("init", initial)
    }

    // Standard method
    String getSecret() {
        return secret
    }

    // Method with default arguments
    def doSomething(int a, String b = "default") {
        return "$a : $b" // GString
    }

    // Closure usage
    void closureExample() {
        def list = [1, 2, 3]
        list.each { item ->
            println "Item: ${item}"
        }
        
        def map = [key: "value", "complex-key": 123]
        map.each { k, v ->
            println "$k -> $v"
        }
    }

    // Switch case (often complex to parse)
    String checkValue(def val) {
        switch (val) {
            case "foo": return "bar"
            case Integer: return "number"
            case [1, 2, 3]: return "list"
            default: return "unknown"
        }
    }

    // Try-catch-finally
    void errorHandling() {
        try {
            throw new RuntimeException("boom")
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Multi-catch
            println "Caught specific"
        } catch (Exception e) {
            println "Caught generic"
        } finally {
            println "Done"
        }
    }

    // Loops
    void loops() {
        for (int i = 0; i < 10; i++) {
            break
        }
        
        for (String s : ["a", "b"]) {
            continue
        }
        
        while(false) {
            // nothing
        }
    }
    
    // Elvis operator and Safe navigation
    void safeOps(KitchenSink other) {
        def val = other?.secret ?: "default"
        def casted = (String) val
        def typeChecked = val instanceof String
    }
    
    // Annotations on local vars
    void annotations() {
        @SuppressWarnings("unused")
        int x = 0
    }
    
    // Inner class
    static class Inner {
        String name
    }
    
    // Anonymous inner class
    Runnable runner = new Runnable() {
        @Override
        void run() {
            println "Running"
        }
    }
}

// Traits
trait Helper {
    void help() { println "Helping" }
}

// Enum
enum Status {
    ON, OFF, UNKNOWN
}

// Script code at bottom
def sink = new KitchenSink("start")
sink.closureExample()

