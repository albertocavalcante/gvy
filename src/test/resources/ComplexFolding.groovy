import java.util.List
import java.util.Map
import java.util.Set
import java.util.HashMap
import java.util.ArrayList

class ComplexFoldingExample {

    def simpleMethod() {
        println "Simple method"
    }

    def methodWithClosure() {
        def list = [1, 2, 3, 4, 5]

        // Closure with parameter
        list.each { item ->
            println "Processing item: $item"
            if (item % 2 == 0) {
                println "Even number"
            } else {
                println "Odd number"
            }
        }

        // Simple closure
        list.collect {
            it * 2
        }
    }

    def methodWithControlStructures() {
        for (i in 1..10) {
            println "For loop iteration: $i"
        }

        while (true) {
            println "While loop"
            break
        }

        if (true) {
            println "If block"
        } else {
            println "Else block"
        }
    }

    def methodWithTryCatch() {
        try {
            println "Trying something"
            throw new Exception("Test exception")
        } catch (Exception e) {
            println "Caught exception: ${e.message}"
            e.printStackTrace()
        } finally {
            println "Finally block"
        }
    }

    class InnerClass {
        def innerMethod() {
            return "Inner method"
        }
    }
}
