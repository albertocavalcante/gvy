println 'hello'

def hello() {
    println
}

class HelloWorld {
    private static final HELLO = 'hello'

    def print() {
        println HELLO
        println(HELLO)
    }
}
