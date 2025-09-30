package test

import groovy.transform.CompileStatic
import java.util.concurrent.Callable

@CompileStatic
class ComplexTypes {
    // Property with getter/setter
    String title

    // List property
    List<String> items = []

    // Map property
    Map<String, Object> properties = [:]

    // Closure property
    Closure<String> formatter = { String input ->
        "Formatted: ${input.toUpperCase()}"
    }

    // Method with closure parameter
    void processItems(Closure processor) {
        items.each { item ->
            processor.call(item)
        }
    }

    // Method returning closure
    Closure<Integer> createMultiplier(int factor) {
        return { int value -> value * factor }
    }

    // Generic method
    def <T> T findFirst(List<T> list, Closure<Boolean> predicate) {
        for (T item : list) {
            if (predicate.call(item)) {
                return item
            }
        }
        return null
    }

    // Method with default parameters
    String buildMessage(String text, String prefix = "Info", boolean upper = false) {
        String result = "${prefix}: ${text}"
        return upper ? result.toUpperCase() : result
    }

    // Static factory method
    static ComplexTypes createWithItems(String... items) {
        def instance = new ComplexTypes()
        instance.items.addAll(items)
        return instance
    }

    // Builder pattern method
    ComplexTypes addProperty(String key, Object value) {
        properties[key] = value
        return this
    }

    // Method using spread operator
    void printAll(Object... args) {
        args.each { println it }
    }

    // Method with GString
    String createSummary() {
        """
        Title: ${title ?: 'Untitled'}
        Items count: ${items.size()}
        Properties count: ${properties.size()}
        """
    }
}
