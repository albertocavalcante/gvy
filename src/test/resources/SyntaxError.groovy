package test

class SyntaxError {
    String name

    // Missing opening brace
    void badMethod( {
        println "This method has syntax errors"
    }

    // Missing closing parenthesis
    void anotherBadMethod(String param {
        return param.toUpperCase()
    }

    // Invalid field declaration
    String = "invalid field"

    // Missing semicolon after statement (intentional - Groovy doesn't require it but this creates parsing issues)
    void problematicMethod() {
        def x = 1
        def y = 2 +  // incomplete expression
        println x + y
    }
}
