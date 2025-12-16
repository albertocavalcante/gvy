package com.example.testlib;

/**
 * A simple greeter class for E2E testing of go-to-definition.
 * This class is intentionally minimal to serve as a test fixture.
 */
public class Greeter {
    private final String name;

    /**
     * Creates a new Greeter with the given name.
     * @param name The name to greet
     */
    public Greeter(String name) {
        this.name = name;
    }

    /**
     * Returns a greeting message.
     * @return The greeting string
     */
    public String greet() {
        return "Hello, " + name + "!";
    }

    /**
     * Returns the name used by this greeter.
     * @return The name
     */
    public String getName() {
        return name;
    }
}
